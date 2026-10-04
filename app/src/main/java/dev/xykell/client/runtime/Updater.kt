package dev.xykell.client.runtime

import org.json.JSONObject

/**
 * Local update metadata model and validation. No remote fetch is
 * performed — this is purely a local metadata abstraction. A real
 * update endpoint would be integrated by swapping the metadata
 * provider implementation.
 */
object Updater {

    data class UpdateMetadata(
        val version: String,
        val changelog: String,
        val url: String,
        val checksum: String? = null,
        val minClientVersion: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private const val METADATA_FILE = "update_metadata.json"
    private const val MAX_CHANGELOG_LENGTH = 10000

    /** Current client version for comparison. */
    val currentVersion: String = dev.xykell.client.runtime.XykellInfo.XYKELL_VERSION

    /** Compare two semantic version strings.
     *  Returns negative if a < b, zero if equal, positive if a > b.
     *  Supports basic semver (major.minor.patch[-suffix]). */
    fun compareVersions(a: String, b: String): Int {
        val (aMajor, aMinor, aPatch, aSuffix) = parseVersion(a)
        val (bMajor, bMinor, bPatch, bSuffix) = parseVersion(b)
        if (aMajor != bMajor) return aMajor - bMajor
        if (aMinor != bMinor) return aMinor - bMinor
        if (aPatch != bPatch) return aPatch - bPatch
        // Compare suffixes (pre-release versions are "less than" release)
        if (aSuffix.isEmpty() && bSuffix.isEmpty()) return 0
        if (aSuffix.isEmpty()) return 1 // release > pre-release
        if (bSuffix.isEmpty()) return -1
        return aSuffix.compareTo(bSuffix)
    }

    /** Version components: (major, minor, patch, suffix) */
    private data class VersionParts(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val suffix: String
    )

    private fun parseVersion(version: String): VersionParts {
        // Parse major.minor.patch[-suffix]
        val parts = version.split('-', limit = 2)
        val main = parts[0].split('.')
        val major = main.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = main.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = main.getOrNull(2)?.toIntOrNull() ?: 0
        val suffix = if (parts.size > 1) parts[1] else ""
        return VersionParts(major, minor, patch, suffix)
    }

    /** Validate update metadata JSON. Returns null if valid, error message if invalid. */
    fun validateMetadata(json: String): String? {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return "Invalid JSON"
        }

        val version = obj.optString("version", "")
        if (version.isBlank()) return "Missing version"

        val changelog = obj.optString("changelog", "")
        if (changelog.length > MAX_CHANGELOG_LENGTH) return "Changelog too long"

        val url = obj.optString("url", "")
        if (url.isNotBlank() && !url.startsWith("https://")) {
            return "URL must use HTTPS"
        }

        val checksum = obj.optString("checksum", "")
        if (checksum.isNotBlank() && !isValidChecksum(checksum)) {
            return "Invalid checksum format (expected SHA-256 hex)"
        }

        val minVersion = obj.optString("minClientVersion", "")
        if (minVersion.isNotBlank() && !isValidVersionFormat(minVersion)) {
            return "Invalid minClientVersion format"
        }

        return null
    }

    /** Load update metadata from local file. */
    fun loadLocalMetadata(context: android.content.Context): UpdateMetadata? {
        val file = java.io.File(context.filesDir, "update_metadata.json")
        if (!file.exists()) return null
        val json = try { file.readText() } catch (e: Exception) { return null }
        return parseMetadata(json)
    }

    /** Parse metadata JSON into UpdateMetadata. Returns null if invalid. */
    fun parseMetadata(json: String): UpdateMetadata? {
        val error = validateMetadata(json)
        if (error != null) return null
        val obj = JSONObject(json)
        return UpdateMetadata(
            version = obj.optString("version", ""),
            changelog = obj.optString("changelog", ""),
            url = obj.optString("url", ""),
            checksum = obj.optString("checksum", null),
            minClientVersion = obj.optString("minClientVersion", null),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        )
    }

    /** Check if an update is available (local metadata only). */
    fun isUpdateAvailable(context: android.content.Context): Boolean {
        val meta = loadLocalMetadata(requireContext(context))
        return meta?.let { compareVersions(currentVersion, it.version) < 0 } ?: false
    }

    internal fun isValidChecksum(checksum: String): Boolean {
        // SHA-256 hex: 64 hex characters
        return Regex("^[a-fA-F0-9]{64}$").matches(checksum)
    }

    internal fun isValidVersionFormat(version: String): Boolean {
        return Regex("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.]+)?$").matches(version)
    }

    /** Helper to get Context from any Fragment/Activity context. */
    private fun requireContext(context: Any): android.content.Context =
        if (context is android.content.Context) context else throw IllegalArgumentException("Not a Context")
}