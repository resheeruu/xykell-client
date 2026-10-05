package dev.xykell.client.runtime.world

import org.json.JSONArray
import org.json.JSONObject

/**
 * Local waypoint book. App-level only: coordinates are entered by the user or
 * derived from an observed PlayerTravelled sample, never read out of the game.
 *
 * Persisted as declarative JSON with a schema version, so a future migration
 * has somewhere to go and a corrupt file can be refused rather than crashing.
 */
class WaypointStore {

    data class Waypoint(
        val id: String,
        val name: String,
        val dimension: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val colorArgb: Int,
        val showOnMap: Boolean,
    ) {
        /** Validation is part of the model, so an invalid point cannot exist. */
        fun isSane(): Boolean =
            id.isNotEmpty() && id.length <= MAX_ID &&
                name.isNotEmpty() && name.length <= MAX_NAME &&
                DIMENSIONS.contains(dimension) &&
                x.isFinite() && y.isFinite() && z.isFinite() &&
                Math.abs(x) <= MAX_COORD && Math.abs(y) <= MAX_COORD_Y &&
                Math.abs(z) <= MAX_COORD
    }

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Invalid(val detail: String) : Result<Nothing>()
        data class Failed(val detail: String) : Result<Nothing>()
    }

    private val points = LinkedHashMap<String, Waypoint>()

    fun all(): List<Waypoint> = points.values.toList()

    fun size(): Int = points.size

    fun get(id: String): Waypoint? = points[id]

    fun put(w: Waypoint): Result<Unit> {
        if (!w.isSane()) return Result.Invalid("waypoint fields out of range")
        points[w.id] = w
        return Result.Ok(Unit)
    }

    fun remove(id: String): Boolean = points.remove(id) != null

    fun clear() = points.clear()

    /** Deterministic order for rendering: name, then id. */
    fun sorted(): List<Waypoint> = points.values.sortedWith(
        compareBy({ it.name.lowercase() }, { it.id }),
    )

    fun serialize(): String {
        val arr = JSONArray()
        for (w in sorted()) {
            arr.put(
                JSONObject()
                    .put("id", w.id)
                    .put("name", w.name)
                    .put("dimension", w.dimension)
                    .put("x", w.x)
                    .put("y", w.y)
                    .put("z", w.z)
                    .put("color", w.colorArgb)
                    .put("showOnMap", w.showOnMap),
            )
        }
        return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("waypoints", arr).toString()
    }

    /**
     * Replaces the book from JSON. A malformed document is refused wholesale:
     * half-restoring a book is worse than leaving the previous one intact.
     */
    fun deserialize(json: String): Result<Int> {
        if (json.isBlank()) {
            points.clear()
            return Result.Ok(0)
        }
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return Result.Invalid("malformed json: ${e.javaClass.simpleName}")
        }
        val version = root.optInt("schemaVersion", 1)
        if (version > SCHEMA_VERSION) {
            return Result.Invalid("document version $version > supported $SCHEMA_VERSION")
        }
        val arr = root.optJSONArray("waypoints")
            ?: return Result.Invalid("missing waypoints array")
        if (arr.length() > MAX_POINTS) return Result.Invalid("too many waypoints")
        val staged = LinkedHashMap<String, Waypoint>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return Result.Invalid("entry $i is not an object")
            val w = Waypoint(
                id = o.optString("id"),
                name = o.optString("name"),
                dimension = o.optString("dimension", DIM_OVERWORLD),
                x = o.optDouble("x", Double.NaN),
                y = o.optDouble("y", Double.NaN),
                z = o.optDouble("z", Double.NaN),
                colorArgb = o.optInt("color", DEFAULT_COLOR),
                showOnMap = o.optBoolean("showOnMap", true),
            )
            if (!w.isSane()) return Result.Invalid("entry $i failed validation")
            staged[w.id] = w
        }
        points.clear()
        points.putAll(staged)
        return Result.Ok(points.size)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_POINTS = 2000
        const val MAX_ID = 64
        const val MAX_NAME = 64
        const val MAX_COORD = 30_000_000.0
        const val MAX_COORD_Y = 20_000.0
        const val DEFAULT_COLOR = 0xFF4FD8C7.toInt()
        const val DIM_OVERWORLD = "overworld"
        const val DIM_NETHER = "nether"
        const val DIM_END = "end"
        val DIMENSIONS = setOf(DIM_OVERWORLD, DIM_NETHER, DIM_END)
    }
}
