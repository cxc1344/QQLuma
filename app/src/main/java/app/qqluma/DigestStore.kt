package app.qqluma

import org.json.JSONObject
import java.io.File

/** One QQ group's digest-mode state. */
data class DigestGroup(val id: String, val name: String, val enabled: Boolean)

/**
 * Per-group digest （概括） mode state — per-group digest mode, driven by a single chat instruction:
 * he says one sentence in chat, the agent (DSH) calls the tool, and digest mode flips
 * for exactly that group.
 *
 * The state file is the contract between the app and the in-sandbox agent:
 *   /qqluma-data/digest/groups.json
 *   { "groups": { "<groupId>": { "name": "...", "enabled": true, "updatedAt": 123 } } }
 * The app reads/writes it through this store; the agent reads/writes it through the
 * bundled `qqluma-digest` command (assets/bin/qqluma-digest, installed into the data
 * dir by Sandbox.ensureDataLayout). Writes are atomic (tmp file + rename) on both
 * sides so a reader never sees a torn file.
 *
 * Digest mode semantics (the old class-group lurking playbook): for an enabled group
 * the agent only collects messages and produces summaries on schedule/on request —
 * it never speaks in that group.
 */
object DigestStore {
    private fun file(sandbox: Sandbox): File =
        File(File(sandbox.dataDir, "digest").apply { mkdirs() }, "groups.json")

    fun list(sandbox: Sandbox): List<DigestGroup> {
        val f = file(sandbox)
        if (!f.isFile) return emptyList()
        val root = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyList()
        val groups = root.optJSONObject("groups") ?: return emptyList()
        val out = mutableListOf<DigestGroup>()
        val keys = groups.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val g = groups.optJSONObject(id) ?: continue
            out += DigestGroup(
                id = id,
                name = g.optString("name", ""),
                enabled = g.optBoolean("enabled", false)
            )
        }
        return out.sortedBy { it.id }
    }

    fun set(sandbox: Sandbox, groupId: String, groupName: String, enabled: Boolean) {
        val f = file(sandbox)
        val root = if (f.isFile) {
            runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject()
        } else JSONObject()
        val groups = root.optJSONObject("groups") ?: JSONObject().also { root.put("groups", it) }
        val entry = JSONObject()
            .put("name", groupName)
            .put("enabled", enabled)
            .put("updatedAt", System.currentTimeMillis())
        groups.put(groupId, entry)
        val tmp = File(f.parentFile, "groups.json.tmp")
        tmp.writeText(root.toString(2))
        tmp.renameTo(f)
    }

    fun isEnabled(sandbox: Sandbox, groupId: String): Boolean =
        list(sandbox).any { it.id == groupId && it.enabled }
}
