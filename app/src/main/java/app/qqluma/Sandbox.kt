package app.qqluma

import android.content.Context
import android.util.Log
import java.io.File

/**
 * QQLuma's single persistent PRoot/Ubuntu sandbox. This is a Termux-like Linux userspace,
 * NOT a VM or a kernel security boundary.
 *
 * Layout (all under the app's private filesDir, which Android does not wipe on its own):
 *  - linux-rootfs/qqluma — the Ubuntu tree (guest /). Treated as rebuildable: nothing
 *    irreplaceable may live here, and LinuxRuntime heals/repairs it instead of wiping it.
 *  - sandbox/qqluma      — guest /workspace, working files.
 *  - qqluma-data         — guest /qqluma-data, OUTSIDE the rootfs: SnowLuma/QQ session
 *    state, the DSH profile and the user's API-key config live here, so they survive any
 *    rootfs rebuild. This split is the whole point of QQLuma.
 */
class Sandbox(context: Context) {
    private val app = context.applicationContext

    /** Host-side workspace, bound into the guest as /workspace. */
    val dir: File = File(app.filesDir, "sandbox/$ID")

    /** Host-side persistent data dir, bound into the guest as /qqluma-data. */
    val dataDir: File = File(app.filesDir, "qqluma-data")

    /** The unpacked Ubuntu tree (guest /). */
    val rootfs: File = File(app.filesDir, "linux-rootfs/$ID")

    fun exec(
        cmd: String,
        timeoutSec: Long = 120,
        onProgress: ((String) -> Unit)? = null,
        onLine: ((String) -> Unit)? = null
    ): String {
        if (cmd.isBlank()) return "(空命令)"
        // Working-directory persistence across one-shot executions, same trick as BotHive:
        // the state file lives in the shared workspace so both sides see it.
        val cwdFile = File(dir, ".qq-cwd")
        val saved = runCatching { cwdFile.readText().trim() }.getOrNull()
            ?.takeIf { it.startsWith("/") && !it.contains('\n') && !it.contains('"') && !it.contains('$') && !it.contains('`') }
        val wrapped = buildString {
            if (saved != null) append("cd \"").append(saved).append("\" 2>/dev/null || cd /workspace\n")
            append(cmd)
            append("\n__qq_rc=\$?; pwd > /workspace/.qq-cwd 2>/dev/null; exit \$__qq_rc")
        }
        if (onProgress != null) LinuxRuntime.progressListener = onProgress
        val result = try {
            LinuxRuntime.exec(
                context = app,
                botId = ID,
                rootfs = rootfs,
                workspace = dir,
                dataDir = dataDir,
                command = wrapped,
                timeoutSec = timeoutSec,
                onLine = onLine,
                capSec = maxOf(180L, timeoutSec)
            )
        } catch (e: Exception) {
            Log.e("QQLumaLinux", "exec failed", e)
            runCatching { File(app.cacheDir, "linux-last-error.log").writeText(Log.getStackTraceString(e)) }
            "[Linux 环境错误] " + LinuxRuntime.describe(e).let { d ->
                if (e is LinuxSetupException) e.message ?: d else d
            }
        }
        if (onProgress != null) LinuxRuntime.progressListener = null
        return result
    }

    /** Host-side health snapshot for the UI. Never triggers an install or any mutation. */
    fun status(): String {
        val marker = File(rootfs, ".qqluma-rootfs")
        val pending = File(rootfs, ".qqluma-provision-pending").isFile
        val apt = TarExtract.resolveInGuest(rootfs, "usr/bin/apt")?.isFile == true
        val bash = TarExtract.resolveInGuest(rootfs, "bin/bash")?.isFile == true
        val backups = rootfs.parentFile?.list()?.filter { it.contains("rootfs-bak") } ?: emptyList()
        return buildString {
            appendLine("rootfs: ${rootfs.path}")
            appendLine("存在=${rootfs.exists()} · 标记=${if (marker.isFile) runCatching { marker.readText().trim() }.getOrElse { "?" } else "无"} · 待装标记=$pending")
            appendLine("核心完整: apt=$apt bash=$bash")
            appendLine("工作区: ${dir.path} 存在=${dir.exists()}")
            appendLine("数据区: ${dataDir.path} 存在=${dataDir.exists()}")
            appendLine("rootfs 备份: ${if (backups.isEmpty()) "无" else backups.joinToString()}")
        }
    }

    /** True once the guest stack (desktop + QQ + SnowLuma) finished installing. */
    fun stackInstalled(): Boolean = File(dataDir, "snowluma/stack-installed").isFile

    /** Host-side handle on the live QR screenshot the guest watcher publishes. */
    fun qrImageFile(): File = File(dataDir, "snowluma/qr.png")

    /** Latest guest-published login status JSON ({"qq":...,"w":..,"h":..,"ts":..}). */
    fun qrStatusJson(): String? =
        runCatching { File(dataDir, "snowluma/status.json").readText().trim() }.getOrNull()

    /**
     * Creates the data-dir layout and installs the app-owned guest scripts:
     * bin/qqluma-digest, the stack scripts under stack/, and host-init.sh (the resident
     * host's entry point). All of these are ours, so an app upgrade refreshes them; the
     * user's session data (snowluma/home, dsh config) is never touched here.
     *
     * host-init.sh contract: when the stack is installed it hands over to stack-run.sh
     * (Xvfb + QQ with the hook shim + SnowLuma, HOME/XDG under /qqluma-data so the QQ
     * session survives rootfs rebuilds); otherwise it just parks so the supervisor's
     * resident process stays alive.
     */
    fun ensureDataLayout() {
        dataDir.mkdirs()
        File(dataDir, "snowluma/home").mkdirs()
        File(dataDir, "dsh").mkdirs()
        File(dataDir, "logs").mkdirs()
        runCatching {
            val binDir = File(dataDir, "bin").apply { mkdirs() }
            val tool = File(binDir, "qqluma-digest")
            app.assets.open("bin/qqluma-digest").use { input ->
                tool.outputStream().use { input.copyTo(it) }
            }
            tool.setExecutable(true, false)
        }
        runCatching {
            val stackDir = File(dataDir, "stack").apply { mkdirs() }
            for (name in listOf("stack-install.sh", "stack-run.sh", "qr-watch.sh", "hook-shim.c")) {
                val out = File(stackDir, name)
                app.assets.open("stack/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                if (name.endsWith(".sh")) out.setExecutable(true, false)
            }
            // The bundled SnowLuma tarball is ~52MB: copy it once, then only
            // re-copy when its size stops matching (app upgrade). The asset
            // name ends in .pack because aapt2 gunzips *.gz assets while
            // packaging; the guest-side file keeps the canonical .tar.gz name.
            val tarOut = File(stackDir, "SnowLuma-v2.2.1-linux-arm64.tar.gz")
            if (!tarOut.exists() || tarOut.length() != 52_110_194L) {
                app.assets.open("stack/SnowLuma-v2.2.1-linux-arm64.tar.gz.pack").use { input ->
                    tarOut.outputStream().use { input.copyTo(it) }
                }
            }
        }
        File(dataDir, "host-init.sh").writeText(
            """
            #!/bin/sh
            # QQLuma resident init (app-managed; refreshed on upgrade).
            mkdir -p /qqluma-data/snowluma/home /qqluma-data/dsh /qqluma-data/logs
            echo "[host-init] resident up"
            if [ -f /qqluma-data/snowluma/stack-installed ]; then
              exec sh /qqluma-data/stack/stack-run.sh
            fi
            echo "[host-init] stack not installed yet; parking"
            exec sleep infinity
            """.trimIndent() + "\n"
        )
    }

    /**
     * Host-side snapshot of the whole data dir into filesDir/backups/, keeping the newest
     * [keep]. Cheap insurance before upgrades and experiments: the data dir holds the QQ
     * session, so a good snapshot is the difference between "restore" and "scan again".
     * Returns the snapshot dir, or null when there is nothing worth snapshotting yet.
     */
    fun snapshotData(keep: Int = 3): File? {
        if (!dataDir.exists() || (dataDir.list()?.isEmpty() != false)) return null
        val backupsRoot = File(app.filesDir, "backups").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val dest = File(backupsRoot, "data-$stamp")
        copyTree(dataDir, dest)
        backupsRoot.listFiles { f -> f.isDirectory && f.name.startsWith("data-") }
            ?.sortedByDescending { it.name }
            ?.drop(keep)
            ?.forEach { TarExtract.deleteTree(it) }
        return dest
    }

    private fun copyTree(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { copyTree(it, File(dest, it.name)) }
        } else {
            dest.parentFile?.mkdirs()
            src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
    }

    /** Test harness only: corrupt the bookkeeping markers the way BotHive's bug feared most. */
    fun damageMarkersForTest() {
        File(rootfs, ".qqluma-rootfs").delete()
        File(rootfs, ".qqluma-provision-pending").writeText("pending\n")
    }

    /**
     * Full wipe. Explicit user action only — nothing in the app calls this automatically,
     * and the resident host must be stopped first.
     */
    fun reset() {
        TarExtract.deleteTree(dir)
        TarExtract.deleteTree(dataDir)
        TarExtract.deleteTree(rootfs)
        dir.mkdirs()
        dataDir.mkdirs()
    }

    companion object {
        const val ID = "qqluma"
    }
}
