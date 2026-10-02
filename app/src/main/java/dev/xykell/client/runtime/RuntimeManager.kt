package dev.xykell.client.runtime

/** Launch backend state. Nothing here launches anything yet. */
enum class LaunchState { NOT_WIRED }

object RuntimeManager {
    val launchState: LaunchState = LaunchState.NOT_WIRED

    /** Human-readable status for the PLAY button area. Never claims more. */
    fun playStatusText(): String = when (launchState) {
        LaunchState.NOT_WIRED ->
            "PLAY = verified handoff: pre-checks, then opens LeviLauncher. " +
            "Tap PLAY to run the checks."
    }
}
