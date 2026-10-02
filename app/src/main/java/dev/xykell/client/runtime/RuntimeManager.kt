package dev.xykell.client.runtime

/** Launch backend state. Nothing here launches anything yet. */
enum class LaunchState { NOT_WIRED }

object RuntimeManager {
    val launchState: LaunchState = LaunchState.NOT_WIRED

    /** Human-readable status for the PLAY button area. Never claims more. */
    fun playStatusText(): String =
        "PLAY runs staged checks and stops at the missing loader stage. " +
        "Tap PLAY to see the exact pipeline result."
}
