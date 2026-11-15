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

    fun exec(cmd: String, timeoutSec: Long = 120, onProgress: ((String) -> Unit)? = null): String {
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
                timeoutSec = timeoutSec
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

    /**
     * Creates the data-dir layout and a default host-init.sh (the resident host's entry
     * point) when absent. The init script encodes the SnowLuma launch contract learned from
     * the mature astrbot-for-android-snowluma project: HOME/XDG live under /qqluma-data so
     * the QQ session survives rootfs rebuilds, and the hook autoload stays off so the QQ
     * session is never hooked twice. Never overwrites an existing host-init.sh.
     */
    fun ensureDataLayout() {
        dataDir.mkdirs()
        File(dataDir, "snowluma/home").mkdirs()
        File(dataDir, "dsh").mkdirs()
        File(dataDir, "logs").mkdirs()
        // Install/refresh the agent's command-line tools into the data dir (ours, so an
        // app upgrade may overwrite them — unlike the user's host-init.sh below).
        runCatching {
            val binDir = File(dataDir, "bin").apply { mkdirs() }
            val tool = File(binDir, "qqluma-digest")
            app.assets.open("bin/qqluma-digest").use { input ->
                tool.outputStream().use { input.copyTo(it) }
            }
            tool.setExecutable(true, false)
        }
        val init = File(dataDir, "host-init.sh")
        if (!init.exists()) {
            init.writeText(
                """
                #!/bin/sh
                # QQLuma resident init: runs inside the sandbox at every host start.
                # Output lands in /qqluma-data/host.log on the host side.
                set -e
                mkdir -p /qqluma-data/snowluma/home /qqluma-data/dsh /qqluma-data/logs
                echo "[host-init] ${'$'}(date) resident up"
                # --- SnowLuma (milestone 2; PIN v2.2.1: the v2.3.x hook crashes Linux QQ
                # 3.2.34 on PRoot) ---
                # export HOME=/qqluma-data/snowluma/home
                # export XDG_CONFIG_HOME=${'$'}HOME/.config
                # export XDG_DATA_HOME=${'$'}HOME/.local/share
                # export XDG_CACHE_HOME=${'$'}HOME/.cache
                # export SNOWLUMA_HOOK_AUTOLOAD=0
                # export SNOWLUMA_WEBUI_HOST=127.0.0.1
                # export SNOWLUMA_WEBUI_PORT=5099
                # Launch happens once the desktop/QQ session exists; see GOAL.md milestone 2.
                """.trimIndent() + "\n"
            )
        }
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
