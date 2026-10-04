package dev.xykell.client.ui

enum class StatusTone { OK, WARN, ERROR, MUTED }

data class StatusRow(val label: String, val value: String, val tone: StatusTone)

object HomeStatus {
    fun rows(
        minecraftVersion: String?,
        profile: String,
        xykellVersion: String,
        nativeVersion: String,
        runtimeSummary: String,
        observationState: String
    ): List<StatusRow> = listOf(
        minecraftRow(minecraftVersion),
        profileRow(profile),
        StatusRow("Xykell", "$xykellVersion (native $nativeVersion)", StatusTone.OK),
        StatusRow("Runtime", runtimeLine(runtimeSummary), runtimeTone(runtimeSummary)),
        StatusRow("Observation", observationState, obsTone(observationState))
    )

    fun runtimeLine(summary: String): String {
        val first = summary.lineSequence().firstOrNull().orEmpty()
        return first.removePrefix("Runtime:").trim().ifEmpty { "unknown" }
    }

    fun runtimeTone(summary: String): StatusTone =
        if (summary.contains("unavailable")) StatusTone.ERROR else StatusTone.MUTED

    fun obsTone(state: String): StatusTone = when (state) {
        "OBSERVING" -> StatusTone.OK
        "STARTING" -> StatusTone.WARN
        "FAILED" -> StatusTone.ERROR
        else -> StatusTone.MUTED
    }

    private fun minecraftRow(version: String?): StatusRow =
        if (version != null) StatusRow("Minecraft", "$version (installed)", StatusTone.OK)
        else StatusRow("Minecraft", "not installed", StatusTone.WARN)

    private fun profileRow(profile: String): StatusRow =
        if (profile.isBlank()) StatusRow("Profile", "none", StatusTone.MUTED)
        else StatusRow("Profile", profile, StatusTone.OK)
}
