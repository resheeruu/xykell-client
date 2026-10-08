package dev.xykell.client.runtime.modules

/**
 * The active profile's module flags, read from the same native profile store
 * [dev.xykell.client.ui.ModulesFragment] writes through.
 *
 * The relay cannot ask the UI what is enabled — it runs in a foreground service
 * with no fragment — so it asks the profile JSON, which is the one place the
 * Modules screen already writes. A missing bridge or an unparseable profile
 * returns false for every id rather than a guess: a module the user did not
 * enable must never run because the store was unreadable.
 *
 * Pure on purpose: the profile JSON string comes in as an argument, so this is
 * host-testable with no JNI and no Android.
 */
object ModuleFlags {

    /**
     * `isEnabled(profileJson, id)` — true only when the profile's `modules`
     * object has an explicit true for [id].
     *
     * Parsed with `org.json`, the same parser the UI uses, so there is no second
     * dialect of the profile format.
     */
    fun isEnabled(profileJson: String?, id: String): Boolean {
        if (profileJson.isNullOrBlank()) return false
        return try {
            val modules = org.json.JSONObject(profileJson).optJSONObject("modules")
            modules?.optBoolean(id, false) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * A predicate over [profileJson] for [ModuleRuntime].
     *
     * The snapshot is taken once per read of the JSON, not per id: the runtime
     * asks the predicate for each id on each packet, and re-parsing the profile
     * that many times per packet is work the phone does not need. The caller
     * re-reads the JSON when it wants a change to take effect.
     */
    fun predicate(profileJson: String?): (String) -> Boolean {
        val modules = try {
            profileJson?.takeIf { it.isNotBlank() }
                ?.let { org.json.JSONObject(it).optJSONObject("modules") }
        } catch (e: Exception) {
            null
        }
        return { id -> modules?.optBoolean(id, false) == true }
    }
}