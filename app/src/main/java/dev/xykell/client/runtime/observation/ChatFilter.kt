package dev.xykell.client.runtime.observation

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Presentation policy for observed chat.
 *
 * Operates only on lines the read-only observation layer actually received.
 * It never contacts the game, never alters what the game shows other players,
 * and never re-sends anything: it decides what *Xykell* displays locally.
 *
 *  - [ChatFilter]    rule-based suppression, with a hard cap on rules and
 *                    pattern length, and a fail-open policy on bad patterns
 *  - [NicknameMap]   local display-name mapping for observed senders
 *  - timestamps      come from the observed event time, not the render clock
 */
class ChatFilter(val maxRules: Int = MAX_RULES) {

    enum class Action { HIDE, HIGHLIGHT }

    data class Rule(
        val id: String,
        val pattern: String,
        val action: Action,
        val caseSensitive: Boolean,
        /** Filled in by ChatFilter.compile; callers never pass it. */
        val compiled: Pattern? = null,
        /** A rule whose pattern would not compile is inert, and says so. */
        val valid: Boolean = compiled != null,
    )

    data class Config(
        val showTimestamps: Boolean,
        val timestampFormat: String,
        val rules: List<Rule>,
    ) {
        companion object {
            val DEFAULT = Config(
                showTimestamps = true,
                timestampFormat = "HH:mm:ss",
                rules = emptyList(),
            )
        }
    }

    private val ruleList = ArrayList<Rule>()

    fun rules(): List<Rule> = synchronized(this) { ruleList.toList() }

    /** Replaces the rule set. Rules are validated; invalid ones are kept but inert. */
    fun replace(config: Config) {
        synchronized(this) {
            ruleList.clear()
            config.rules.take(maxRules).forEach { ruleList.add(compile(it)) }
        }
    }

    fun add(rule: Rule): ApiResult {
        if (rule.id.isEmpty() || rule.id.length > MAX_ID) {
            return ApiResult.Invalid("rule id must be 1..$MAX_ID chars")
        }
        if (rule.pattern.isEmpty() || rule.pattern.length > MAX_PATTERN) {
            return ApiResult.Invalid("pattern must be 1..$MAX_PATTERN chars")
        }
        return synchronized(this) {
            if (ruleList.size >= maxRules) {
                return ApiResult.Invalid("at most $maxRules rules")
            }
            if (ruleList.any { it.id == rule.id }) {
                return ApiResult.Invalid("duplicate rule id ${rule.id}")
            }
            ruleList.add(compile(rule))
            ApiResult.Ok
        }
    }

    fun remove(ruleId: String): Boolean = synchronized(this) {
        val before = ruleList.size
        ruleList.removeAll { it.id == ruleId }
        ruleList.size != before
    }

    fun clear() {
        synchronized(this) { ruleList.clear() }
    }

    private fun compile(r: Rule): Rule {
        val flags = if (r.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        val compiled = try {
            Pattern.compile(r.pattern, flags)
        } catch (e: PatternSyntaxException) {
            // Fail open: a bad pattern must not silently hide messages.
            null
        }
        return r.copy(compiled = compiled, valid = compiled != null)
    }

    data class Verdict(
        val line: ObservedState.ChatLine,
        val visible: Boolean,
        val highlighted: Boolean,
        val displaySender: String,
        val timestamp: String?,
    )

    /**
     * Applies the rules to one observed line. Rule order is significant and
     * deterministic: the first HIGHLIGHT wins for highlighting, and HIDE wins
     * over HIGHLIGHT for suppression.
     */
    fun evaluate(
        line: ObservedState.ChatLine,
        nowMs: Long,
        nicknames: NicknameMap,
        config: Config = Config.DEFAULT,
    ): Verdict {
        val snapshot = synchronized(this) { ruleList.toList() }
        var visible = true
        var highlighted = false
        for (r in snapshot) {
            val p = r.compiled ?: continue
            if (!p.matcher(line.message).find()) continue
            when (r.action) {
                Action.HIDE -> visible = false
                Action.HIGHLIGHT -> highlighted = true
            }
        }
        return Verdict(
            line = line,
            visible = visible,
            highlighted = highlighted,
            displaySender = nicknames.displayName(line.sender),
            timestamp = if (config.showTimestamps) formatTime(line.atMs) else null,
        )
    }

    fun visibleLines(
        lines: List<ObservedState.ChatLine>,
        nowMs: Long,
        nicknames: NicknameMap,
        config: Config = Config.DEFAULT,
    ): List<Verdict> = lines.map { evaluate(it, nowMs, nicknames, config) }.filter { it.visible }

    companion object {
        const val MAX_RULES = 100
        const val MAX_PATTERN = 256
        const val MAX_ID = 64

        /** UTC, so a rendered log is reproducible on any device. */
        fun formatTime(atMs: Long): String {
            val s = atMs / 1000
            val h = (s / 3600) % 24
            val m = (s / 60) % 60
            val sec = s % 60
            return "%02d:%02d:%02d".format(h, m, sec)
        }
    }
}

/** Local display-name mapping for observed senders. Display only. */
class NicknameMap(private val maxEntries: Int = MAX_ENTRIES) {

    private val map = LinkedHashMap<String, String>()

    fun entries(): Map<String, String> = synchronized(this) { LinkedHashMap(map) }

    fun displayName(sender: String): String =
        synchronized(this) { map[sender] ?: sender }

    fun set(sender: String, nickname: String): ApiResult {
        if (sender.isEmpty() || sender.length > MAX_LEN) {
            return ApiResult.Invalid("sender must be 1..$MAX_LEN chars")
        }
        if (nickname.isEmpty() || nickname.length > MAX_LEN) {
            return ApiResult.Invalid("nickname must be 1..$MAX_LEN chars")
        }
        // Rendering, not injection: no control characters reach the HUD.
        if (nickname.any { it.code < 0x20 || it.code == 0x7f }) {
            return ApiResult.Invalid("nickname contains control characters")
        }
        if (nickname.contains('/') || nickname.contains('\\')) {
            return ApiResult.Invalid("nickname contains a path separator")
        }
        return synchronized(this) {
            if (map.size >= maxEntries && sender !in map) {
                return ApiResult.Invalid("at most $maxEntries nicknames")
            }
            map[sender] = nickname
            ApiResult.Ok
        }
    }

    fun remove(sender: String): Boolean = synchronized(this) { map.remove(sender) != null }

    fun clear() {
        synchronized(this) { map.clear() }
    }

    private companion object {
        const val MAX_ENTRIES = 500
        const val MAX_LEN = 32
    }
}

sealed class ApiResult {
    data object Ok : ApiResult()
    data class Invalid(val detail: String) : ApiResult()
    data class Failed(val detail: String) : ApiResult()
}
