package app.qqluma

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Setup/launch failure that names the failing step and carries diagnostics, so a screenshot is enough to debug. */
internal class LinuxSetupException(
    val step: String,
    detail: String,
    cause: Throwable? = null,
    val diag: String = ""
) : IOException(
    "[" + step + "] " + detail + (if (diag.isNotBlank()) "\n诊断: $diag" else ""),
    cause
)

/**
 * Downloads and unpacks an Ubuntu Base rootfs for each Bot, then executes commands through
 * the Android PRoot binary packaged in nativeLibraryDir by tools/fetch_proot_android.sh.
 * The compressed archive is cached once per device ABI; unpacked rootfs trees are per-Bot.
 */
internal object LinuxRuntime {
    private const val UBUNTU_VERSION = "24.04.3"
    private const val MAX_ROOTFS_ARCHIVE_BYTES = 64L * 1024L * 1024L
    private const val DOWNLOAD_DEADLINE_MS = 300_000L
    private const val TOTAL_STEPS = 9
    private const val USER_AGENT = "QQLuma-Android/1.0.0"

    /** First-run provisioning inside the fresh rootfs; failure is soft (install still succeeds). */
    private const val PROVISION_CMD =
        "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && " +
        "apt-get install -y -qq --no-install-recommends python3 nodejs git curl jq zip unzip ca-certificates ffmpeg && " +
        "echo PROVISION_OK && python3 --version && node --version"

    /** Ubuntu cdimage first; a public mirror of cdimage as fallback. Checksums come from the same directory's SHA256SUMS. */
    private val MIRRORS = listOf(
        "cdimage.ubuntu.com" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release",
        "mirrors.tuna.tsinghua.edu.cn" to "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release"
    )

    private val botLocks = ConcurrentHashMap<String, Any>()
    private val processSlots = Semaphore(2, true)

    /** Optional progress sink for the onboarding UI: receives human-readable step lines. */
    @Volatile
    var progressListener: ((String) -> Unit)? = null

    internal fun reportProgress(line: String) {
        runCatching { progressListener?.invoke(line) }
    }

    @Volatile
    private var seccompDisabled = false

    private data class Abi(val android: String, val ubuntu: String, val loader: String)

    private val supported = listOf(
        Abi("arm64-v8a", "arm64", "ld-linux-aarch64.so.1"),
        Abi("armeabi-v7a", "armhf", "ld-linux-armhf.so.3"),
        Abi("x86_64", "amd64", "ld-linux-x86-64.so.2")
    )

    fun describe(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() }
        val head = if (e.javaClass == IOException::class.java) (msg ?: "IOException") else "${e.javaClass.simpleName}: ${msg ?: "(无消息)"}"
        val c = e.cause
        return if (c != null && c !== e && c.message != e.message) "$head ← ${c.javaClass.simpleName}: ${c.message}" else head
    }

    fun exec(
        context: Context,
        botId: String,
        rootfs: File,
        workspace: File,
        dataDir: File,
        command: String,
        timeoutSec: Long,
        onLine: ((String) -> Unit)? = null,
        capSec: Long = 180
    ): String {
        val abi = supported.firstOrNull { it.android in Build.SUPPORTED_ABIS }
            ?: throw LinuxSetupException(
                "步骤 1/$TOTAL_STEPS 检查设备",
                "当前设备 ABI 不在 PRoot 支持列表中",
                diag = "supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}"
            )
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libloader.so")
        if (!proot.isFile || !loader.isFile) {
            throw LinuxSetupException(
                "步骤 1/$TOTAL_STEPS 检查设备",
                "APK 缺少 PRoot 运行文件。请先运行 bash tools/fetch_proot_android.sh，再重新构建 APK",
                diag = "nativeDir=${nativeDir.path} files=${nativeDir.list()?.sorted()?.joinToString().orEmpty()}"
            )
        }

        val lock = botLocks.getOrPut(botId) { Any() }
        synchronized(lock) {
            ensureInstalled(context, botId, abi, rootfs, workspace, dataDir)
            workspace.mkdirs()
            dataDir.mkdirs()
            if (!processSlots.tryAcquire(120, TimeUnit.SECONDS)) {
                throw IOException("Linux 并发任务已满，请稍后重试")
            }
            try {
                return runProot(context, botId, proot, loader, rootfs, workspace, dataDir, command, timeoutSec, onLine, capSec)
            } finally {
                processSlots.release()
            }
        }
    }

    /**
     * Ubuntu's bin/sh is a relative symlink (→ dash). Links are still resolved inside the rootfs,
     * never against the host. The marker records the distro and provisioning state.
     */
    private fun isInstalled(rootfs: File): Boolean {
        val marker = File(rootfs, ".qqluma-rootfs")
        if (!marker.isFile) return false
        val text = try { marker.readText() } catch (_: Exception) { return false }
        if (!text.startsWith("Ubuntu")) return false
        if (File(rootfs, ".qqluma-provision-pending").isFile) return false
        return coreIntact(rootfs)
    }

    /** True when the guest's core binaries actually resolve and exist, whatever the markers say. */
    private fun coreIntact(rootfs: File): Boolean =
        TarExtract.resolveInGuest(rootfs, "usr/bin/apt")?.isFile == true &&
            TarExtract.resolveInGuest(rootfs, "bin/bash")?.isFile == true

    /**
     * QQLuma persistence red line: markers are bookkeeping, not truth. If the core is intact but
     * the markers are damaged (deleted marker, stale provision-pending flag), heal them in place
     * and keep every byte of the existing tree — BotHive's reinstall-on-marker-failure is exactly
     * the data-loss bug QQLuma exists to fix. A full install runs only when the core is truly gone,
     * and even then install() parks the old tree as a backup instead of deleting it.
     */
    private fun ensureInstalled(
        context: Context,
        botId: String,
        abi: Abi,
        rootfs: File,
        workspace: File,
        dataDir: File
    ) {
        if (isInstalled(rootfs)) {
            reportProgress("环境已就绪")
            return
        }
        if (coreIntact(rootfs)) {
            reportProgress("标记损坏但核心完好 · 原地修复，不重装")
            runCatching {
                File(rootfs, ".qqluma-rootfs").writeText("Ubuntu $UBUNTU_VERSION\n")
                File(rootfs, ".qqluma-provision-pending").delete()
            }
            if (isInstalled(rootfs)) return
        }
        Installer(context, botId, abi, rootfs, workspace, dataDir).install()
    }

    private class Installer(
        val context: Context,
        val botId: String,
        val abi: Abi,
        val rootfs: File,
        val workspace: File,
        val dataDir: File
    ) {
        private val diag = StringBuilder()

        private fun note(key: String, value: Any?) {
            if (diag.isNotEmpty()) diag.append(" · ")
            diag.append(key).append('=').append(value)
        }

        private fun <T> step(n: Int, name: String, block: () -> T): T = try {
            reportProgress("步骤 $n/$TOTAL_STEPS · $name")
            block()
        } catch (e: LinuxSetupException) {
            throw e
        } catch (e: Exception) {
            throw LinuxSetupException("步骤 $n/$TOTAL_STEPS $name", describe(e), e, diag.toString())
        }

        private fun <T> tag(label: String, block: () -> T): T = try {
            block()
        } catch (e: Exception) {
            throw IOException("$label: ${describe(e)}", e)
        }

        fun install() {
            note("abi", abi.android)
            note("ubuntu", UBUNTU_VERSION)
            val parent = step(2, "准备目录") {
                val p = rootfs.parentFile ?: throw IOException("rootfs 路径没有父目录: ${rootfs.path}")
                if (!p.exists() && !p.mkdirs()) throw IOException("无法创建 ${p.path}")
                p
            }
            val staging = File(parent, ".$botId.installing")
            step(2, "准备目录") {
                TarExtract.deleteTree(staging)
                if (!staging.mkdirs()) throw IOException("无法创建临时目录 ${staging.path}")
            }
            try {
                val archive = fetchArchive()
                val result = step(5, "解压 Ubuntu") { TarExtract.extractTarGz(archive, staging) }
                note("extracted", result.summary())
                step(6, "完整性检查") { verifyLayout(staging) }
                step(7, "安装 rootfs") {
                    File(staging, "root").mkdirs()
                    File(staging, "tmp").mkdirs()
                    File(staging, "workspace").mkdirs()
                    writeResolver(staging)
                    File(staging, ".qqluma-rootfs").writeText("Ubuntu $UBUNTU_VERSION\n")
                    File(staging, ".qqluma-provision-pending").writeText("pending\n")
                    // QQLuma red line: never delete an existing tree. Park it as a timestamped
                    // backup so a reinstall can never take user state (logins, packages) with it.
                    if (rootfs.exists()) {
                        val bak = File(parent, ".$botId.rootfs-bak-${System.currentTimeMillis()}")
                        if (!rootfs.renameTo(bak)) TarExtract.deleteTree(rootfs)
                    }
                    if (!staging.renameTo(rootfs)) {
                        throw IOException("重命名失败 ${staging.path} → ${rootfs.path}")
                    }
                }
                step(8, "预装常用软件") { provision() }
                workspace.mkdirs()
            } catch (e: Exception) {
                try { TarExtract.deleteTree(staging) } catch (_: Exception) {}
                throw e
            }
        }

        /**
         * Runs apt inside the freshly installed rootfs (via PRoot) to pre-install the common toolchain.
         * Soft-fail: on any failure the pending flag is removed and a note file records what happened,
         * so the environment stays usable and the user can apt-install manually later.
         */
        private fun provision() {
            workspace.mkdirs()
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val proot = File(nativeDir, "libproot.so")
            val loader = File(nativeDir, "libloader.so")
            try {
                val r = runProotOnce(context, botId, proot, loader, rootfs, workspace, dataDir, PROVISION_CMD, 900, false, 900)
                val ok = !r.timedOut && r.exit == 0 && "PROVISION_OK" in r.text
                File(rootfs, ".qqluma-provision-pending").delete()
                if (!ok) {
                    note("provision", "failed exit=${r.exit}")
                    File(rootfs, ".qqluma-provision-note").writeText(
                        "预装未完成(exit ${r.exit}${if (r.timedOut) ",超时" else ""}),可让 Bot 手动执行 apt-get update && apt-get install -y python3 nodejs git curl jq zip unzip ffmpeg。输出摘要:${r.text.take(500)}"
                    )
                } else {
                    note("provision", "ok")
                }
            } catch (e: Exception) {
                File(rootfs, ".qqluma-provision-pending").delete()
                note("provision", "error ${describe(e)}")
                runCatching {
                    File(rootfs, ".qqluma-provision-note").writeText("预装异常:${describe(e)},可手动 apt 安装。")
                }
            }
        }

        private fun verifyLayout(root: File) {
            val problems = mutableListOf<String>()
            val bash = TarExtract.resolveInGuest(root, "bin/bash")
            if (bash == null || !bash.isFile) problems.add("bin/bash 在 rootfs 内解析不到")
            else if (!bash.canExecute()) problems.add("bin/bash 没有可执行权限")
            val apt = File(root, "usr/bin/apt")
            if (!apt.isFile || apt.length() == 0L) problems.add("usr/bin/apt 缺失或为空")
            val rel = File(root, "etc/os-release")
            if (!rel.isFile) problems.add("etc/os-release 缺失")
            else if (!rel.readText().contains("Ubuntu")) problems.add("etc/os-release 不是 Ubuntu")
            val loader = TarExtract.resolveInGuest(root, "lib/${abi.loader}")
            if (loader == null || !loader.isFile) problems.add("lib/${abi.loader} 在 rootfs 内解析不到")
            if (problems.isNotEmpty()) throw IOException("rootfs 内容不完整: " + problems.joinToString("; "))
        }

        /** Returns a verified archive: previously verified local copy first, then each mirror in turn. */
        private fun fetchArchive(): File {
            reportProgress("步骤 3/$TOTAL_STEPS · 下载 Ubuntu 基础镜像（约 30MB）")
            val name = "ubuntu-base-$UBUNTU_VERSION-base-${abi.ubuntu}.tar.gz"
            val cache = File(context.cacheDir, name)
            val okFile = File(context.cacheDir, "$name.sha256ok")

            val local = step(3, "检查本地缓存") {
                if (cache.isFile && okFile.isFile) {
                    val want = okFile.readText().trim()
                    if (want.length == 64 && sha256(cache) == want) cache else null
                } else null
            }
            if (local != null) {
                note("source", "local-cache")
                note("archive", "${local.length() / 1024}KB")
                return local
            }

            val failures = mutableListOf<String>()
            for ((host, base) in MIRRORS) {
                val url = "$base/$name"
                val part = File(context.cacheDir, "$name.part")
                try {
                    val expected = tag("取 SHA256 校验文件") { fetchSha256For("$base/SHA256SUMS", name) }
                    part.delete()
                    val bytes = tag("下载压缩包") { download(url, part) }
                    val actual = tag("计算 SHA256") { sha256(part) }
                    if (actual != expected) {
                        throw IOException("SHA256 不一致: 期望 ${expected.take(12)}… 实际 ${actual.take(12)}…(收到 $bytes 字节)")
                    }
                    cache.delete()
                    if (!part.renameTo(cache)) throw IOException("无法把下载文件移入缓存 ${cache.path}")
                    okFile.writeText(expected)
                    note("source", host)
                    note("archive", "${bytes / 1024}KB")
                    note("sha256", expected.take(12) + "…")
                    return cache
                } catch (e: Exception) {
                    part.delete()
                    failures.add("$host → ${describe(e)}")
                }
            }
            throw LinuxSetupException(
                "步骤 3/$TOTAL_STEPS 下载 Ubuntu",
                "所有下载源均失败:\n" + failures.joinToString("\n"),
                diag = diag.toString()
            )
        }
    }

    // ---- network ----

    /** Follows redirects manually (HTTPS only) so proxy/VPN/CDN hops are visible and never downgrade to HTTP. */
    private fun open(startUrl: String, readTimeoutMs: Int): HttpURLConnection {
        var url = URL(startUrl)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept-Encoding", "identity") // never let a proxy gunzip a .tar.gz for us
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw IOException("HTTP $code 重定向但缺少 Location(${url.host})")
                val next = URL(url, loc)
                if (next.protocol != "https") throw IOException("拒绝重定向到非 HTTPS 地址: ${next.protocol}://${next.host}")
                url = next
            } else {
                if (code !in 200..299) {
                    val msg = c.responseMessage
                    c.disconnect()
                    throw IOException("HTTP $code ${msg ?: ""} (${url.host})")
                }
                return c
            }
        }
        throw IOException("重定向次数过多(${url.host})")
    }

    /** SHA256SUMS lists one file per line ("<hash> *<name>"); pick the line for our archive. */
    private fun fetchSha256For(sumsUrl: String, fileName: String): String {
        val c = open(sumsUrl, 30_000)
        try {
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(16_384) }
            val line = text.lineSequence().firstOrNull { it.contains(fileName) }
                ?: throw IOException("SHA256SUMS 中找不到 $fileName")
            return Regex("(?i)(?<![0-9a-f])([0-9a-f]{64})(?![0-9a-f])").find(line)?.groupValues?.get(1)?.lowercase()
                ?: throw IOException("校验行无法解析:'${line.take(80)}'")
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, target: File): Long {
        val c = open(url, 60_000)
        try {
            val length = c.contentLengthLong
            if (length > MAX_ROOTFS_ARCHIVE_BYTES) throw IOException("Content-Length $length 超过上限 ${MAX_ROOTFS_ARCHIVE_BYTES / 1024 / 1024}MB")
            val deadline = System.currentTimeMillis() + DOWNLOAD_DEADLINE_MS
            var total = 0L
            c.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ROOTFS_ARCHIVE_BYTES) throw IOException("下载内容超过上限 ${MAX_ROOTFS_ARCHIVE_BYTES / 1024 / 1024}MB")
                        if (System.currentTimeMillis() > deadline) throw IOException("下载超时(已收到 $total 字节)")
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (length >= 0 && total != length) throw IOException("下载不完整: 收到 $total 字节, 期望 $length 字节")
            return total
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeResolver(rootfs: File) {
        val etc = File(rootfs, "etc")
        etc.mkdirs()
        val resolv = File(etc, "resolv.conf")
        // Remove a rootfs-provided symlink before writing, so it cannot redirect writes outside rootfs.
        try { java.nio.file.Files.deleteIfExists(resolv.toPath()) } catch (_: Exception) {}
        val servers = mutableListOf<String>()
        for (key in listOf("net.dns1", "net.dns2", "net.dns3", "net.dns4")) {
            runCatching {
                val p = ProcessBuilder("/system/bin/getprop", key).start()
                if (p.waitFor(1, TimeUnit.SECONDS)) {
                    val value = p.inputStream.bufferedReader().use { it.readText().trim() }
                    if (value.matches(Regex("[0-9a-fA-F:.]+")) && value !in servers) servers.add(value)
                    Unit
                } else p.destroyForcibly()
            }
        }
        if (servers.isEmpty()) servers.addAll(listOf("223.5.5.5", "1.1.1.1"))
        resolv.writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    // ---- running commands ----

    private data class ProotRun(val text: String, val exit: Int, val timedOut: Boolean, val limit: Long)

    private fun render(r: ProotRun): String {
        val body = if (r.text.isBlank()) "" else r.text
        val tail = if (r.timedOut) "\n[执行超时 ${r.limit}s，已请求终止]" else "\n[exit ${r.exit}]"
        val joined = body + tail
        return if (joined.isBlank()) "(无输出)" else if (joined.length > 4_000) joined.take(4_000) + "\n…(已截断)" else joined
    }

    /** proot's own startup failures print "proot error: ..." and exit non-zero before the guest command ever runs. */
    private fun prootItselfFailed(r: ProotRun) = !r.timedOut && r.exit != 0 && r.text.trimStart().lowercase().startsWith("proot")

    /** Only seccomp/ptrace-looking startup failures justify the PROOT_NO_SECCOMP retry; option/usage errors do not. */
    private fun looksSeccompRelated(r: ProotRun): Boolean {
        val t = r.text.lowercase()
        return prootItselfFailed(r) && ("seccomp" in t || "ptrace" in t || "operation not permitted" in t)
    }

    private fun runProot(
        context: Context,
        botId: String,
        proot: File,
        loader: File,
        rootfs: File,
        workspace: File,
        dataDir: File,
        command: String,
        timeoutSec: Long,
        onLine: ((String) -> Unit)? = null,
        capSec: Long = 180
    ): String {
        val first = runProotOnce(context, botId, proot, loader, rootfs, workspace, dataDir, command, timeoutSec, seccompDisabled, capSec = capSec, onLine = onLine)
        if (!seccompDisabled && looksSeccompRelated(first)) {
            // Some kernels/ROMs reject PRoot's seccomp acceleration; retry once without it (the guest command never ran).
            val second = runProotOnce(context, botId, proot, loader, rootfs, workspace, dataDir, command, timeoutSec, true, capSec = capSec, onLine = onLine)
            if (!prootItselfFailed(second)) {
                seccompDisabled = true
                return render(second) + "\n[PRoot 已自动改用 PROOT_NO_SECCOMP=1]"
            }
            return render(first) + "\n[已用 PROOT_NO_SECCOMP=1 重试，仍失败]\n" + render(second)
        }
        return render(first)
    }

    private fun runProotOnce(
        context: Context,
        botId: String,
        proot: File,
        loader: File,
        rootfs: File,
        workspace: File,
        dataDir: File,
        command: String,
        timeoutSec: Long,
        noSeccomp: Boolean,
        capSec: Long = 180,
        onLine: ((String) -> Unit)? = null
    ): ProotRun {
        val temp = File(context.cacheDir, "proot-tmp/$botId").apply { mkdirs() }
        val args = ProotArgs.build(proot.absolutePath, rootfs.absolutePath, workspace.canonicalPath, dataDir.canonicalPath, command)
        val pb = ProcessBuilder(args).directory(workspace).redirectErrorStream(true)
        pb.environment().apply {
            clear() // Do not leak unrelated Android process environment into the guest.
            put("PROOT_LOADER", loader.absolutePath)
            put("PROOT_TMP_DIR", temp.absolutePath)
            put("LD_LIBRARY_PATH", proot.parentFile?.absolutePath.orEmpty())
            if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
            put("HOME", "/root")
            put("USER", "root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("TMPDIR", "/tmp")
            put("LANG", "C.UTF-8")
        }
        val process = try {
            pb.start()
        } catch (e: Exception) {
            throw LinuxSetupException(
                "步骤 9/$TOTAL_STEPS 启动 PRoot",
                describe(e),
                e,
                "proot=${proot.path} exists=${proot.exists()} canExecute=${proot.canExecute()} size=${proot.length()} " +
                    "loader=${loader.exists()} workspace=${workspace.exists()} rootfs=${rootfs.exists()} noSeccomp=$noSeccomp"
            )
        }
        val output = StringBuilder()
        val cap = if (onLine != null) 400_000 else 20_000
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(1024)
                    val partial = StringBuilder()
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(output) {
                            if (output.length < cap) output.append(buf, 0, n)
                        }
                        if (onLine != null) {
                            partial.append(buf, 0, n)
                            var nl = partial.indexOf("\n")
                            while (nl >= 0) {
                                val line = partial.substring(0, nl).trimEnd('\r')
                                partial.delete(0, nl + 1)
                                runCatching { onLine(line) }
                                nl = partial.indexOf("\n")
                            }
                        }
                    }
                    if (onLine != null && partial.isNotEmpty()) {
                        runCatching { onLine(partial.toString().trimEnd('\r')) }
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val limit = timeoutSec.coerceIn(1, capSec)
        var timedOut = false
        var exit = -1
        if (!process.waitFor(limit, TimeUnit.SECONDS)) {
            timedOut = true
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        } else {
            exit = process.exitValue()
        }
        reader.join(1_000)
        return ProotRun(synchronized(output) { output.toString() }, exit, timedOut, limit)
    }

    /**
     * Starts ONE long-lived PRoot process for the resident host service: no timeout, output
     * appended to [logFile]. The guest runs /qqluma-data/host-init.sh first (that is where
     * SnowLuma/QQ will hook in), then parks in `sleep infinity` so the PRoot session stays
     * alive until the host service stops it. The caller owns the returned Process and is
     * responsible for restarting it when it exits.
     */
    fun startResident(
        context: Context,
        botId: String,
        rootfs: File,
        workspace: File,
        dataDir: File,
        logFile: File
    ): Process {
        val abi = supported.firstOrNull { it.android in Build.SUPPORTED_ABIS }
            ?: throw LinuxSetupException("步骤 1/$TOTAL_STEPS 检查设备", "当前设备 ABI 不在 PRoot 支持列表中")
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libloader.so")
        if (!proot.isFile || !loader.isFile) {
            throw LinuxSetupException("步骤 1/$TOTAL_STEPS 检查设备", "APK 缺少 PRoot 运行文件。请先运行 bash tools/fetch_proot_android.sh，再重新构建 APK")
        }
        val lock = botLocks.getOrPut(botId) { Any() }
        synchronized(lock) {
            ensureInstalled(context, botId, abi, rootfs, workspace, dataDir)
        }
        workspace.mkdirs()
        dataDir.mkdirs()
        logFile.parentFile?.mkdirs()
        // Reap leftovers from a previous incarnation before starting a new resident: if the
        // app process was force-killed, its PRoot tree can keep living as an orphan, holding
        // the sandbox and (once SnowLuma exists) its WebUI port. The bracketed patterns can
        // never match the reaper's own command line. On in-service restarts the old process
        // was already waited on, so this is a no-op. Pattern learned from the mature
        // astrbot-for-android-snowluma project (MIT), which hit the same orphan problem.
        runCatching {
            runProotOnce(
                context, botId, proot, loader, rootfs, workspace, dataDir,
                "pkill -f '[h]ost-init.sh' 2>/dev/null; pkill -f '[i]ndex.mjs' 2>/dev/null; " +
                    "pkill -f '[s]leep infinity' 2>/dev/null; echo REAPED",
                15, seccompDisabled
            )
        }
        val temp = File(context.cacheDir, "proot-tmp/$botId").apply { mkdirs() }
        val command = "mkdir -p /qqluma-data /workspace; " +
            "if [ -f /qqluma-data/host-init.sh ]; then bash /qqluma-data/host-init.sh; fi; " +
            "exec sleep infinity"
        val args = ProotArgs.build(
            proot.absolutePath, rootfs.absolutePath,
            workspace.canonicalPath, dataDir.canonicalPath, command
        )
        val pb = ProcessBuilder(args).directory(workspace).redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", loader.absolutePath)
            put("PROOT_TMP_DIR", temp.absolutePath)
            put("LD_LIBRARY_PATH", proot.parentFile?.absolutePath.orEmpty())
            if (seccompDisabled) put("PROOT_NO_SECCOMP", "1")
            put("HOME", "/root")
            put("USER", "root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("TMPDIR", "/tmp")
            put("LANG", "C.UTF-8")
        }
        return pb.start()
    }
}
