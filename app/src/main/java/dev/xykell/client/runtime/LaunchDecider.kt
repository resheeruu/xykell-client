package dev.xykell.client.runtime

/** Pure launch decision (no Android APIs — reviewable, mirrors native policy).
 *  Full in-Levi configured launch is NOT available: Levi exposes no external
 *  action carrying version+isolation+mods, so Xykell hands off to Levi's own
 *  MainActivity after passing pre-checks. Firing a bare minecraft:// URI
 *  would open the game WITHOUT Xykell — explicitly rejected. */
enum class LaunchDecision {
    /** MC verdict allows + Levi present: open Levi MainActivity. */
    HANDOFF_TO_LEVI,
    MISSING_MINECRAFT,
    MISSING_LEVI,
    UNSUPPORTED_VERSION,
    NATIVE_BRIDGE_DOWN,
}

object LaunchDecider {
    fun decide(
        mcInstalled: Boolean,
        leviInstalled: Boolean,
        verdictState: String,
        bridgeUp: Boolean
    ): LaunchDecision {
        if (!bridgeUp) return LaunchDecision.NATIVE_BRIDGE_DOWN
        if (!mcInstalled) return LaunchDecision.MISSING_MINECRAFT
        if (verdictState != "SUPPORTED" && verdictState != "PARTIAL") {
            return LaunchDecision.UNSUPPORTED_VERSION
        }
        if (!leviInstalled) return LaunchDecision.MISSING_LEVI
        return LaunchDecision.HANDOFF_TO_LEVI
    }

    fun describe(d: LaunchDecision): String = when (d) {
        LaunchDecision.HANDOFF_TO_LEVI ->
            "Pre-checks passed. Opening LeviLauncher — complete the launch " +
            "inside Levi (isolated version + Xykell mod enabled). " +
            "Xykell does NOT launch the game directly."
        LaunchDecision.MISSING_MINECRAFT ->
            "PLAY blocked: official Minecraft Bedrock is not installed."
        LaunchDecision.MISSING_LEVI ->
            "PLAY blocked: LeviLauncher v1.5.25+ is not installed. " +
            "Install it from github.com/LiteLDev/LeviLaunchroid/releases first."
        LaunchDecision.UNSUPPORTED_VERSION ->
            "PLAY blocked: installed Minecraft build is not Xykell-compatible."
        LaunchDecision.NATIVE_BRIDGE_DOWN ->
            "PLAY blocked: native bridge unavailable."
    }
}
