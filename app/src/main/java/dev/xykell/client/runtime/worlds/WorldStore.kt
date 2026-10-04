package dev.xykell.client.runtime.worlds

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class WorldEntry(
    val id: String,
    val name: String,
    val notes: String = "",
    val favorite: Boolean = false,
    val lastUsed: Long = 0L,
    val createdAt: Long = 0L,
    val gameVersion: String = "",
    val worldPath: String = ""
)

/** Local world book. Pure JVM: validation, JSON persistence with
 *  schema tolerance, sorting, search, and gzipped NBT level.dat
 *  import via SAF tree picker. */
object WorldStore {
    const val MAX_NAME = 64
    const val MAX_NOTES = 500

    fun validate(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "empty name"
        if (n.length > MAX_NAME) return "name over $MAX_NAME chars"
        return null
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun toJson(entries: List<WorldEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject()
                .put("id", e.id)
                .put("name", e.name)
                .put("notes", e.notes)
                .put("favorite", e.favorite)
                .put("lastUsed", e.lastUsed)
                .put("createdAt", e.createdAt)
                .put("gameVersion", e.gameVersion)
                .put("worldPath", e.worldPath))
        }
        return JSONObject().put("version", 1).put("worlds", arr).toString()
    }

    /** Tolerant decode: bad shape returns empty; invalid entries skipped. */
    fun fromJson(json: String): List<WorldEntry> {
        val root = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        val arr = root.optJSONArray("worlds") ?: return emptyList()
        val out = mutableListOf<WorldEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (validate(name) != null) continue
            out += WorldEntry(
                id = o.optString("id", "").ifEmpty { newId() },
                name = name,
                notes = o.optString("notes", "").take(MAX_NOTES),
                favorite = o.optBoolean("favorite", false),
                lastUsed = o.optLong("lastUsed", 0L),
                createdAt = o.optLong("createdAt", 0L),
                gameVersion = o.optString("gameVersion", ""),
                worldPath = o.optString("worldPath", "")
            )
        }
        return out
    }

    /** Favorites first, then most recently used, then name. */
    fun sort(entries: List<WorldEntry>): List<WorldEntry> =
        entries.sortedWith(
            compareByDescending<WorldEntry> { it.favorite }
                .thenByDescending { it.lastUsed }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )

    /** Case-insensitive substring match over name/notes/gameVersion. */
    fun filter(entries: List<WorldEntry>, query: String): List<WorldEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.name.lowercase().contains(q) ||
                it.notes.lowercase().contains(q) ||
                it.gameVersion.lowercase().contains(q)
        }
    }

    /** Extract world metadata from level.dat bytes. Returns map with
     *  keys: LevelName, Generator, GameType, Difficulty, LastPlayed,
     *  SizeOnDisk, Version — or empty if parsing fails. */
    fun extractFromLevelDat(bytes: ByteArray): Map<String, Any> =
        NbtReader.parseLevelDat(bytes)
}