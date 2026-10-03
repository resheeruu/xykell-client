package dev.xykell.client.ui

/**
 * Pure mapping from settings-catalog type strings to row controls
 * (Batch 7). No Android dependency: host JVM tests cover every rule.
 * Sliders appear ONLY for numeric types with a real range; choices get
 * dropdowns; everything else gets type-appropriate editors.
 */
object SettingRowMapper {

    enum class RowKind { TOGGLE, SLIDER_INT, SLIDER_DOUBLE, DROPDOWN, TEXT }

    fun rowKind(type: String, min: Double, max: Double, optionCount: Int): RowKind {
        return when (type) {
            "bool" -> RowKind.TOGGLE
            "int" -> if (max > min) RowKind.SLIDER_INT else RowKind.TEXT
            "double" -> if (max > min) RowKind.SLIDER_DOUBLE else RowKind.TEXT
            "choice" -> if (optionCount >= 2) RowKind.DROPDOWN else RowKind.TEXT
            else -> RowKind.TEXT
        }
    }

    /** Section order for the Settings screen; unknown sections sort last. */
    fun sectionOrder(sections: List<String>): List<String> {
        val preferred = listOf(
            "General", "Appearance", "HUD", "Modules", "Controls",
            "Performance", "Notifications", "Privacy", "Advanced", "About",
        )
        return sections.sortedBy { s ->
            val i = preferred.indexOf(s)
            if (i < 0) Int.MAX_VALUE else i
        }
    }

    /** Native catalog sections mapped onto display sections. */
    fun displaySection(nativeSection: String): String {
        return when (nativeSection) {
            "client" -> "General"
            "hud" -> "HUD"
            "notifications" -> "Notifications"
            "privacy" -> "Privacy"
            "profile" -> "General"
            else -> "Advanced"
        }
    }
}
