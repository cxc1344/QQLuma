package app.qqluma

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Onboarding state + the user's DSH credentials.
 *
 * The API key is BYOK: it is written only into the app-private data dir (as the DSH
 * profile config under /qqluma-data/dsh/config.json, where the in-sandbox DSH will read
 * it) and never leaves the device from here. Plaintext in app-private storage matches
 * how the sandbox itself must consume it; the file is not exportable or backed up
 * (allowBackup=false).
 */
object AppConfig {
    private const val PREFS = "qqluma_prefs"
    private const val KEY_ONBOARDED = "onboarded"

    fun isOnboarded(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(ctx: Context, done: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDED, done).apply()
    }

    /** Persists the DSH profile (base URL + API key) into the persistent data dir. */
    fun saveDshConfig(sandbox: Sandbox, baseUrl: String, apiKey: String) {
        val dir = File(sandbox.dataDir, "dsh").apply { mkdirs() }
        val json = JSONObject()
            .put("baseUrl", baseUrl.trim())
            .put("apiKey", apiKey.trim())
        File(dir, "config.json").writeText(json.toString(2))
    }

    fun hasDshConfig(sandbox: Sandbox): Boolean =
        File(sandbox.dataDir, "dsh/config.json").isFile
}
