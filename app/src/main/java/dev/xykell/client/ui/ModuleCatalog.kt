package dev.xykell.client.ui

import org.json.JSONObject

/**
 * Pure parser/grouping/search over the packaged registry catalog
 * (Batch 12). Reads the same `registry/features.json` shape as native;
 * no Android framework — fully JVM-testable. Malformed JSON yields null
 * so the UI can show an honest unavailable state instead of a fake list.
 */
data class ModuleEntry(
    val id: String,
    val name: String,
    val category: String,
    val status: String,
    val description: String,
    val capabilities: List<String>,
    val implementation: String,
    val riskLevel: String,
    val evidence: String,
    val settingKeys: List<String>,
) {
    /**
     * Profile-preference switches exist only where something can run:
     * SUPPORTED/PARTIAL entries have a real implementation to gate.
     * RESEARCH_REQUIRED / NOT_IMPLEMENTED never get a switch — a switch
     * on those would imply operation that does not exist.
     */
    val supportsPreference: Boolean
        get() = status == "SUPPORTED" || status == "PARTIAL"

    companion object {

        /** Parse the registry document. Null on malformed JSON or a
         *  non-object root. Entries missing id/name/category/status are
         *  skipped as invalid rather than rendered misleadingly. */
        fun parseAll(json: String): List<ModuleEntry>? {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                return null
            }
            val arr = root.optJSONArray("features") ?: return emptyList()
            val out = ArrayList<ModuleEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val entry = parse(o) ?: continue
                out.add(entry)
            }
            return out
        }

        fun parse(o: JSONObject): ModuleEntry? {
            val id = o.optString("id", "")
            val name = o.optString("name", "")
            val category = o.optString("category", "")
            val status = o.optString("status", "")
            if (id.isEmpty() || name.isEmpty() || category.isEmpty() || status.isEmpty()) {
                return null
            }
            return ModuleEntry(
                id = id,
                name = name,
                category = category,
                status = status,
                description = o.optString("description", ""),
                capabilities = stringList(o.optJSONArray("capabilities")),
                implementation = o.optString("implementation", ""),
                riskLevel = o.optString("risk_level", ""),
                evidence = o.optString("evidence", ""),
                settingKeys = settingsKeys(o.optJSONArray("settings")),
            )
        }

        /** Case-insensitive substring match over id, name, description and
         *  category. Blank query returns everything (stable order). */
        fun search(entries: List<ModuleEntry>, query: String): List<ModuleEntry> {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return entries
            return entries.filter { e ->
                e.id.lowercase().contains(q) ||
                    e.name.lowercase().contains(q) ||
                    e.description.lowercase().contains(q) ||
                    e.category.lowercase().contains(q)
            }
        }

        /** Group preserving first-seen category order and registry order
         *  inside each category. */
        fun groupByCategory(entries: List<ModuleEntry>): List<Pair<String, List<ModuleEntry>>> {
            val groups = LinkedHashMap<String, MutableList<ModuleEntry>>()
            for (e in entries) {
                groups.getOrPut(e.category) { ArrayList() }.add(e)
            }
            return groups.map { it.key to it.value }
        }

        private fun stringList(arr: org.json.JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotEmpty()) out.add(v)
            }
            return out
        }

        private fun settingsKeys(arr: org.json.JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key", "")
                if (key.isNotEmpty()) out.add(key)
            }
            return out
        }
    }
}
