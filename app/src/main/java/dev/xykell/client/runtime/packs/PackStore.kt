package dev.xykell.client.runtime.packs

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class PackType { RESOURCE, BEHAVIOR, UNKNOWN }

data class PackEntry(
    val id: String,
    val name: String,
    val type: PackType = PackType.UNKNOWN,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val sizeBytes: Long = 0L,
    val enabled: Boolean = false,
    val notes: String = "",
    val path: String = "",
    val createdAt: Long = 0L
)

/** Local pack book: manual entries + optional manifest.json import
 *  via SAF file picker. Pure JVM: validation, JSON persistence with
 *  schema tolerance, sorting, search, and manifest.json extraction. */
object PackStore {
    const val MAX_NAME = 64
    const val MAX_NOTES = 500
    const val MAX_AUTHOR = 64
    const val MAX_DESCRIPTION = 500

    fun validate(name: String, type: PackType): String? {
        val n = name.trim()
        if (n.isEmpty()) return "empty name"
        if (n.length > MAX_NAME) return "name over $MAX_NAME chars"
        return null
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun toJson(entries: List<PackEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject()
                .put("id", e.id)
                .put("name", e.name)
                .put("type", e.type.name)
                .put("version", e.version)
                .put("author", e.author)
                .put("description", e.description)
                .put("sizeBytes", e.sizeBytes)
                .put("enabled", e.enabled)
                .put("notes", e.notes)
                .put("path", e.path)
                .put("createdAt", e.createdAt))
        }
        return JSONObject().put("version", 1).put("packs", arr).toString()
    }

    /** Tolerant decode: bad shape returns empty; invalid entries skipped. */
    fun fromJson(json: String): List<PackEntry> {
        val root = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        val arr = root.optJSONArray("packs") ?: return emptyList()
        val out = mutableListOf<PackEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            val typeStr = o.optString("type", "UNKNOWN")
            val type = try { PackType.valueOf(typeStr) } catch (e: Exception) { PackType.UNKNOWN }
            if (validate(name, type) != null) continue
            out += PackEntry(
                id = o.optString("id", "").ifEmpty { newId() },
                name = name,
                type = type,
                version = o.optString("version", ""),
                author = o.optString("author", "").take(MAX_AUTHOR),
                description = o.optString("description", "").take(MAX_DESCRIPTION),
                sizeBytes = o.optLong("sizeBytes", 0L),
                enabled = o.optBoolean("enabled", false),
                notes = o.optString("notes", "").take(MAX_NOTES),
                path = o.optString("path", ""),
                createdAt = o.optLong("createdAt", 0L)
            )
        }
        return out
    }

    /** Enabled first, then by name. */
    fun sort(entries: List<PackEntry>): List<PackEntry> =
        entries.sortedWith(
            compareByDescending<PackEntry> { it.enabled }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )

    /** Case-insensitive substring match over name/author/version/notes. */
    fun filter(entries: List<PackEntry>, query: String): List<PackEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.name.lowercase().contains(q) ||
                it.author.lowercase().contains(q) ||
                it.version.lowercase().contains(q) ||
                it.notes.lowercase().contains(q)
        }
    }

    /** Extract pack metadata from manifest.json bytes. Returns map with
     *  keys: name, description, version, author, modules (list of types) —
     *  or empty if parsing fails. */
    fun extractFromManifest(bytes: ByteArray): Map<String, Any> {
        return try {
            val text = String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            val root = JSONObject(text)
            val header = root.optJSONObject("header") ?: return emptyMap()
            val modules = root.optJSONArray("modules") ?: return emptyMap()
            val types = mutableSetOf<PackType>()
            for (i in 0 until modules.length()) {
                val m = modules.optJSONObject(i) ?: continue
                val t = m.optString("type", "")
                when (t) {
                    "resources" -> types += PackType.RESOURCE
                    "data" -> types += PackType.BEHAVIOR
                    "script" -> {} // scripts not a pack type here
                    else -> types += PackType.UNKNOWN
                }
            }
            val primaryType = if (types.contains(PackType.RESOURCE)) PackType.RESOURCE
                else if (types.contains(PackType.BEHAVIOR)) PackType.BEHAVIOR
                else PackType.UNKNOWN
            mapOf(
                "name" to header.optString("name", ""),
                "description" to header.optString("description", ""),
                "version" to header.optString("version", ""),
                "author" to header.optString("author", ""),
                "packType" to primaryType
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }
}