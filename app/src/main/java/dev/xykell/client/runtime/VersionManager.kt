package dev.xykell.client.runtime

data class MinecraftVersion(val label: String, val detail: String)

object VersionManager {
    /** Real scan not implemented — returns empty until Levi integration lands. */
    fun installedVersions(): List<MinecraftVersion> = emptyList()

    fun statusText(): String =
        "NOT WIRED — version scan pending Levi runtime integration."
}
