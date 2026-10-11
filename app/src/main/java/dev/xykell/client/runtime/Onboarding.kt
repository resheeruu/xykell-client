package dev.xykell.client.runtime

import org.json.JSONObject

/**
 * First-run onboarding state and the user's chosen launch mode.
 *
 * **What this is.** A policy object, deliberately pure Kotlin: it holds the
 * decisions, validates them, and serialises them, while the Android layer owns
 * the `SharedPreferences` file. Keeping storage out means the rules — which mode
 * strings are legal, what counts as "onboarding finished" — are host-testable,
 * and it matches how [dev.xykell.client.runtime.observation.ChatPolicy] and
 * [dev.xykell.client.runtime.world.WaypointStore] already split policy from
 * storage.
 *
 * **What this is not.** There is no gate here. No licence check, no ad funnel,
 * no server round trip, no time-limited token. Lumina's equivalent gates app
 * start behind a Linkvertise redirect and a client-side shared salt, which is
 * not a security boundary and is not something this project does — see
 * `docs/LEGAL-LICENSE.md` sections 3 and 5. "First run" here means *show the
 * introduction once*, and that is the entire meaning of the flag.
 *
 * **Launch mode** is the user's choice of how the client reaches the game, kept
 * as a closed set. It is a preference, not a capability: an unknown string is
 * refused rather than stored and later rendered as a blank row.
 */
class Onboarding {

    enum class LaunchMode(val id: String) {
        /** User starts the client, then starts the game themselves. */
        MANUAL("manual"),

        /** The client offers to start the game when it becomes available. */
        WATCH("watch"),

        /** Reserved for the standalone loader path; not yet implemented. */
        STANDALONE("standalone");

        companion object {
            fun fromId(id: String): LaunchMode? = values().firstOrNull { it.id == id }

            val ids: List<String> = values().map { it.id }
        }
    }

    /** True until the introduction has been dismissed once. */
    @Volatile
    var firstRun: Boolean = true

    /** True once the user has seen the introduction, ever. */
    @Volatile
    var introductionSeen: Boolean = false

    /** Null until the user picks one. Not defaulted — a guess is not a choice. */
    @Volatile
    var launchMode: LaunchMode? = null

    /**
     * Whether the launcher should surface a start-game action this session.
     *
     * Only after onboarding is complete: offering a launch action to someone who
     * has not chosen a mode would be acting on a decision they have not made.
     */
    val canOfferLaunch: Boolean get() = introductionSeen && launchMode != null

    /** Marks the introduction as seen and onboarding as complete. Idempotent. */
    fun completeOnboarding(mode: LaunchMode) {
        firstRun = false
        introductionSeen = true
        launchMode = mode
    }

    /** Resets to a fresh install. Used by the "reset onboarding" action. */
    fun reset() {
        firstRun = true
        introductionSeen = false
        launchMode = null
    }

    /**
     * Applies a launch mode from its id. An unknown id is refused and leaves the
     * current mode untouched, so a corrupted stored value cannot half-apply.
     */
    fun setLaunchMode(id: String): Boolean {
        val mode = LaunchMode.fromId(id) ?: return false
        launchMode = mode
        return true
    }

    fun serialize(): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("firstRun", firstRun)
        .put("introductionSeen", introductionSeen)
        .put("launchMode", launchMode?.id ?: JSONObject.NULL)
        .toString()

    /**
     * Restores from [json]. Returns false when the document is refused — a
     * malformed or newer-schema document is left unapplied rather than
     * partially applied, matching the contract the other policy stores use.
     */
    fun deserialize(json: String): Boolean {
        if (json.isBlank()) return true
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return false
        }
        if (root.optInt("schemaVersion", 1) > SCHEMA_VERSION) return false
        val modeId = root.optString("launchMode", "")
        val mode = if (modeId.isEmpty() || root.isNull("launchMode")) {
            null
        } else {
            LaunchMode.fromId(modeId) ?: return false
        }
        firstRun = root.optBoolean("firstRun", true)
        introductionSeen = root.optBoolean("introductionSeen", false)
        launchMode = mode
        return true
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}