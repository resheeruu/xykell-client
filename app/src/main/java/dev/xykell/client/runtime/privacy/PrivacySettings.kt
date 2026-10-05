package dev.xykell.client.runtime.privacy

/**
 * Local privacy and presentation policy for the Xykell shell.
 *
 * All app-level: nothing here touches the game, the network, or any file the
 * user did not open. Each toggle has a defined, testable effect and an honest
 * "what it does not do" boundary, because a privacy feature that implies more
 * coverage than it has is worse than none.
 */
class PrivacySettings(
    private val store: Store,
) {

    /** Minimal persistence so the settings survive a restart. */
    interface Store {
        fun get(key: String): Boolean
        fun put(key: String, value: Boolean)
        fun all(): Map<String, Boolean>
    }

    class MemoryStore(initial: Map<String, Boolean> = emptyMap()) : Store {
        private val map = LinkedHashMap(initial)
        override fun get(key: String) = map[key] ?: false
        override fun put(key: String, value: Boolean) {
            map[key] = value
        }
        override fun all(): Map<String, Boolean> = LinkedHashMap(map)
    }

    /**
     * Streamer mode: keep the Xykell identity off shared screens and out of
     * share sheets. Applies to the app's own chrome.
     *
     * It does NOT hide the game window, does NOT alter the game's own
     * rendering, and does NOT stop a third-party app from photographing the
     * display. Callers must not claim otherwise.
     */
    var streamerMode: Boolean
        get() = store.get(KEY_STREAMER)
        set(value) = store.put(KEY_STREAMER, value)

    /**
     * Privacy mode: withhold diagnostic detail and account identity from
     * anything the user shares or exports.
     *
     * Does NOT disable CrashGuard collection, and does NOT change what the
     * observation pipeline records.
     */
    var privacyMode: Boolean
        get() = store.get(KEY_PRIVACY)
        set(value) = store.put(KEY_PRIVACY, value)

    /** Hides the Xykell overlay chrome while leaving rules and settings live. */
    var hideHud: Boolean
        get() = store.get(KEY_HIDE_HUD)
        set(value) = store.put(KEY_HIDE_HUD, value)

    /** What the share/export path is allowed to include right now. */
    fun redactionPolicy(): Redaction = Redaction(
        includeAccountName = !privacyMode && !streamerMode,
        includeDiagnostics = !privacyMode,
        includeDeviceModel = !streamerMode,
        includeVersion = true,
    )

    data class Redaction(
        val includeAccountName: Boolean,
        val includeDiagnostics: Boolean,
        val includeDeviceModel: Boolean,
        val includeVersion: Boolean,
    ) {
        /** The version is never withheld: an unidentifiable bug report is useless. */
        val anythingIncluded: Boolean
            get() = includeAccountName || includeDiagnostics || includeDeviceModel || includeVersion
    }

    fun snapshot(): Map<String, Boolean> = store.all()

    fun reset() {
        store.put(KEY_STREAMER, false)
        store.put(KEY_PRIVACY, false)
        store.put(KEY_HIDE_HUD, false)
    }

    /** Display label for a settings key, with no hardcoded UI text leaking in. */
    fun isKnown(key: String): Boolean = key in KNOWN_KEYS

    companion object {
        const val KEY_STREAMER = "streamer_mode"
        const val KEY_PRIVACY = "privacy_mode"
        const val KEY_HIDE_HUD = "hide_hud"
        val KNOWN_KEYS = setOf(KEY_STREAMER, KEY_PRIVACY, KEY_HIDE_HUD)
    }
}

/**
 * A countdown timer that is honest about its state: it either has a deadline
 * it will reach, or it is stopped. No fabricated "running" state.
 */
class CountdownTimer(private val clock: () -> Long = System::currentTimeMillis) {

    enum class Phase { IDLE, RUNNING, FINISHED }

    data class Snapshot(
        val state: Phase,
        val remainingMs: Long,
        val totalMs: Long,
    ) {
        val active: Boolean get() = state == Phase.RUNNING
    }

    private var state = Phase.IDLE
    private var totalMs = 0L
    private var endsAtMs = 0L

    fun start(durationMs: Long): ApiResult {
        if (durationMs !in MIN_DURATION..MAX_DURATION) {
            return ApiResult.Invalid("duration must be $MIN_DURATION..$MAX_DURATION ms")
        }
        totalMs = durationMs
        endsAtMs = clock() + durationMs
        state = Phase.RUNNING
        return ApiResult.Ok
    }

    fun stop() {
        state = Phase.IDLE
        totalMs = 0L
        endsAtMs = 0L
    }

    /** Resolves FINISHED exactly once, so a caller cannot double-count it. */
    fun snapshot(): Snapshot {
        if (state == Phase.RUNNING) {
            val remaining = endsAtMs - clock()
            if (remaining <= 0L) {
                state = Phase.FINISHED
            }
        }
        val remaining = when (state) {
            Phase.RUNNING -> (endsAtMs - clock()).coerceAtLeast(0L)
            Phase.FINISHED -> 0L
            Phase.IDLE -> 0L
        }
        return Snapshot(state, remaining, totalMs)
    }

    fun restart(durationMs: Long = totalMs): ApiResult = if (durationMs > 0) start(durationMs) else ApiResult.Invalid("no previous duration")

    fun format(s: Snapshot = snapshot()): String = when (s.state) {
        Phase.IDLE -> "--:--"
        Phase.FINISHED -> "00:00"
        Phase.RUNNING -> {
            val totalSec = s.remainingMs / 1000
            "%02d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }

    companion object {
        const val MIN_DURATION = 1_000L
        /** A day: long enough for a real session, bounded so the label fits. */
        const val MAX_DURATION = 24L * 60L * 60L * 1000L
    }
}

sealed class ApiResult {
    data object Ok : ApiResult()
    data class Invalid(val detail: String) : ApiResult()
}
