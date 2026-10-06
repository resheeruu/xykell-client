package dev.xykell.client.runtime.observation

import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's chat presentation policy: which rules apply and which local
 * display names are used.
 *
 * Rules and nicknames change only what Xykell shows on this device. They
 * never reach the game and never re-send anything.
 *
 * Persisted as declarative JSON with a schema version, matching the contract
 * [dev.xykell.client.runtime.world.WaypointStore] already uses: a malformed
 * document is refused wholesale rather than half-applied, and a newer version
 * is refused rather than silently downgraded.
 */
class ChatPolicy {

    val filter = ChatFilter()
    val nicknames = NicknameMap()

    /** Timestamps are a presentation choice, so they are part of the policy. */
    @Volatile
    var showTimestamps: Boolean = true

    fun config(): ChatFilter.Config = ChatFilter.Config(
        showTimestamps = showTimestamps,
        timestampFormat = ChatFilter.Config.DEFAULT.timestampFormat,
        rules = filter.rules(),
    )

    /** Replaces the whole policy from JSON. Returns false when refused. */
    fun deserialize(json: String): Boolean {
        if (json.isBlank()) return true
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return false
        }
        if (root.optInt("schemaVersion", 1) > SCHEMA_VERSION) return false
        if (root.has("showTimestamps")) {
            showTimestamps = root.optBoolean("showTimestamps", true)
        }
        val rules = root.optJSONArray("rules")
        if (rules != null) {
            if (rules.length() > ChatFilter.MAX_RULES) return false
            val staged = ArrayList<ChatFilter.Rule>(rules.length())
            for (i in 0 until rules.length()) {
                val o = rules.optJSONObject(i) ?: return false
                val action = when (o.optString("action", "HIDE")) {
                    "HIGHLIGHT" -> ChatFilter.Action.HIGHLIGHT
                    else -> ChatFilter.Action.HIDE
                }
                staged.add(
                    ChatFilter.Rule(
                        id = o.optString("id"),
                        pattern = o.optString("pattern"),
                        action = action,
                        caseSensitive = o.optBoolean("caseSensitive", false),
                    ),
                )
            }
            // Rules are validated by ChatFilter.replace; invalid ones are kept
            // but inert, so a bad persisted rule cannot silently hide chat.
            filter.replace(config().copy(rules = staged))
        }
        val nick = root.optJSONObject("nicknames")
        if (nick != null) {
            if (nick.length() > NICKNAME_MAX_ENTRIES) return false
            nicknames.clear()
            for (key in nick.keys()) {
                val value = nick.optString(key)
                if (nicknames.set(key, value) !is ApiResult.Ok) {
                    // One bad entry must not discard the rest: it is skipped,
                    // matching NicknameMap's own rejection of out-of-range input.
                    continue
                }
            }
        }
        return true
    }

    fun serialize(): String {
        val rules = JSONArray()
        for (r in filter.rules()) {
            rules.put(
                JSONObject()
                    .put("id", r.id)
                    .put("pattern", r.pattern)
                    .put("action", r.action.name)
                    .put("caseSensitive", r.caseSensitive),
            )
        }
        val nick = JSONObject()
        for ((sender, display) in nicknames.entries()) {
            nick.put(sender, display)
        }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("showTimestamps", showTimestamps)
            .put("rules", rules)
            .put("nicknames", nick)
            .toString()
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val NICKNAME_MAX_ENTRIES = 500

        @Volatile
        private var shared: ChatPolicy? = null

        /** One policy per process, so the screen and its rules agree. */
        fun shared(): ChatPolicy = shared ?: synchronized(this) {
            shared ?: ChatPolicy().also { shared = it }
        }
    }
}
