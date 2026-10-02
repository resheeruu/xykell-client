package dev.xykell.client.runtime

object ProfileManager {
    val profiles: List<String> = listOf("Default")
    var currentProfile: String = "Default"
        private set

    /** Only "Default" exists in M1.5; anything else is rejected, not faked. */
    fun select(name: String): Boolean {
        if (name !in profiles) return false
        currentProfile = name
        return true
    }
}
