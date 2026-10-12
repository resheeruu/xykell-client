package dev.xykell.client.runtime.toolbox

/**
 * The toolbox's state, as pure functions over the two documents it already owns.
 *
 * A toolbox is quick toggles for modules you would otherwise leave the game to
 * change. It therefore reads the same two things the Modules screen reads --
 * `registry/features.json` for what exists, and the active profile for what is
 * on -- and writes back through the same profile import the Modules screen uses.
 * There is no third source of truth and no second profile dialect.
 *
 * **Why this is a separate object.** The overlay is a window that can be torn
 * down and recreated at any moment, and the bridge can be unavailable at any
 * moment. Keeping the decisions here means "which modules are listed", "what
 * happens when you tap one" and "what happens when the profile is unreadable"
 * are all answerable on the host with no JNI and no Android, which is the only
 * way they get tested at all.
 */
object ToolboxModel {

    /** One toggleable row. */
    data class Row(
        val id: String,
        val name: String,
        val category: String,
        val enabled: Boolean,
    )

    /**
     * What the overlay should show.
     *
     * [rows] is empty and [problem] set when neither document could be read.
     * An empty panel that looks like "no modules exist" would be a lie, so the
     * two are distinguished.
     */
    data class State(
        val rows: List<Row>,
        val problem: String? = null,
        val expanded: Boolean = false,
    ) {
        val enabledCount: Int get() = rows.count { it.enabled }
    }

    /**
     * Builds the row list.
     *
     * Only entries the catalog says are actually supported get a row. A toggle
     * for a RESEARCH_REQUIRED entry would switch something that does nothing,
     * which is worse than not offering it: the user would believe it was on.
     *
     * @param catalogJson `features.json` -- null when it could not be read.
     * @param profileJson the active profile -- null when unreadable.
     */
    fun build(catalogJson: String?, profileJson: String?): State {
        if (catalogJson.isNullOrBlank()) {
            return State(emptyList(), "module catalog unavailable")
        }
        val entries = try {
            dev.xykell.client.ui.ModuleEntry.parseAll(catalogJson)
        } catch (e: Exception) {
            null
        } ?: return State(emptyList(), "module catalog could not be parsed")

        val flags = try {
            val obj = org.json.JSONObject(profileJson ?: return State(emptyList(), null))
            obj.optJSONObject("modules")
        } catch (e: Exception) {
            return State(emptyList(), "active profile could not be read")
        }

        val rows = entries
            .filter { entry -> entry.supportsPreference }
            .map { entry ->
                Row(
                    id = entry.id,
                    name = entry.name,
                    category = entry.category,
                    enabled = flags?.optBoolean(entry.id, false) == true,
                )
            }
        return State(rows)
    }

    /**
     * Returns the profile JSON with one module flag flipped.
     *
     * Returns null when the input cannot be parsed, so a caller cannot write a
     * profile it failed to understand -- losing a profile because a toggle was
     * tapped is not a recoverable mistake.
     *
     * The rest of the document is preserved verbatim. A profile holds more than
     * module flags, and rewriting it from a model of only the flags would drop
     * whatever the Modules screen put there.
     */
    fun withToggled(profileJson: String?, moduleId: String): String? {
        return try {
            val root = org.json.JSONObject(profileJson ?: return null)
            val modules = root.optJSONObject("modules") ?: org.json.JSONObject()
            modules.put(moduleId, !modules.optBoolean(moduleId, false))
            root.put("modules", modules)
            root.toString()
        } catch (e: Exception) {
            null
        }
    }
}