package app.qqluma

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Resident host for the QQ stack: a foreground service supervising ONE long-lived PRoot
 * process (see LinuxRuntime.startResident). The guest runs /qqluma-data/host-init.sh on
 * start — that is where SnowLuma/QQ will be launched in milestone 2 — then parks in
 * `sleep infinity`. If the PRoot process exits while the service is up, the supervisor
 * restarts it after a short backoff; because all session state lives in /qqluma-data
 * (outside the rootfs), a restart never costs a re-login.
 */
class QqHostService : Service() {
    @Volatile
    private var running = false
    private var supervisor: Thread? = null
    private var wakeThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "QQ 宿主", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("QQLuma 宿主运行中")
            .setContentText("QQ 沙箱常驻，进程退出会自动拉回")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            startWakeLock()
            startSupervisor()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { process?.destroy() }
        supervisor?.interrupt()
        wakeThread?.interrupt()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        writeStatus("stopped")
        super.onDestroy()
    }

    /**
     * Partial wake lock on a renewable lease (acquire 10 min, renew every 9 min), the same
     * pattern the mature astrbot-for-android runtime service uses: a plain indefinite hold
     * is both a battery and an OEM-whack-a-mole problem, while no lock lets Doze starve the
     * resident QQ process.
     */
    private fun startWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qqluma:host")
            .apply { setReferenceCounted(false) }
        wakeThread = Thread {
            while (running) {
                runCatching { wakeLock?.acquire(10 * 60 * 1000L) }
                try {
                    Thread.sleep(9 * 60 * 1000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startSupervisor() {
        supervisor = Thread {
            val sandbox = Sandbox(this)
            sandbox.ensureDataLayout()
            // One snapshot per service start: if anything later goes wrong, the QQ session
            // in the data dir can be rolled back instead of re-scanned.
            runCatching { sandbox.snapshotData() }
            val logFile = File(sandbox.dataDir, "host.log")
            var restarts = 0
            while (running) {
                try {
                    writeStatus(if (restarts == 0) "starting" else "restarting #$restarts")
                    val p = LinuxRuntime.startResident(
                        this, Sandbox.ID, sandbox.rootfs, sandbox.dir, sandbox.dataDir, logFile
                    )
                    process = p
                    // Grace period before claiming "running": a resident that dies within
                    // seconds was never up, and the status must not pretend otherwise.
                    Thread.sleep(3_000)
                    if (!p.isAlive) {
                        writeStatus("exited(${runCatching { p.exitValue() }.getOrElse { -1 }})")
                    } else {
                        writeStatus("running")
                        val exit = p.waitFor()
                        writeStatus("exited($exit)")
                    }
                } catch (e: Exception) {
                    writeStatus("error: " + LinuxRuntime.describe(e))
                }
                if (!running) break
                restarts++
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun writeStatus(s: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, s).apply()
        runCatching { File(File(filesDir, "qqluma-data"), "host-status.txt").writeText(s) }
    }

    companion object {
        private const val CHANNEL = "qq_host"
        private const val NOTIF_ID = 43
        private const val PREFS = "qqluma_prefs"
        private const val KEY_STATUS = "host_status"
        private const val KEY_DESIRED = "host_desired"

        /** Whether the user wants the host up; read by the boot receiver before any UI exists. */
        fun desired(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DESIRED, false)

        fun start(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DESIRED, true).apply()
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, QqHostService::class.java))
            } catch (_: Exception) {
                // Some ROMs refuse background FGS starts; the next app open retries.
            }
        }

        fun stop(ctx: Context) {
            // Persist the stop FIRST (synchronously), so a boot or a service restart can
            // never resurrect a host the user explicitly stopped.
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DESIRED, false).commit()
            runCatching { ctx.stopService(Intent(ctx, QqHostService::class.java)) }
        }

        fun status(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATUS, "未启动") ?: "未启动"
    }
}
