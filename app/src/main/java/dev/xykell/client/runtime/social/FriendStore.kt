package dev.xykell.client.runtime.social

import org.json.JSONArray
import org.json.JSONObject

/**
 * Local friends book. App-level only: entries are typed by the user, never
 * inferred from private data and never synced anywhere.
 *
 * Semantics mirror the native FriendManager (native/src/xykell_friends.cpp):
 * name 1..32 chars, unique exact-match, color a literal #RRGGBB — so the
 * Kotlin store and the native store accept and reject the same documents.
 */
class FriendStore {

    data class Friend(
        val name: String,
        val color: String,
        val notes: String,
        val server: String,
    ) {
        fun isSane(): Boolean =
            isValidName(name) && isValidColor(color) &&
                notes.length <= MAX_NOTES && server.length <= MAX_SERVER
    }

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Invalid(val detail: String) : Result<Nothing>()
    }

    private val friends = LinkedHashMap<String, Friend>()

    fun all(): List<Friend> = friends.values.toList()

    fun size(): Int = friends.size

    fun isFriend(name: String): Boolean = friends.containsKey(name)

    fun add(f: Friend): Result<Unit> {
        if (!isValidName(f.name)) return Result.Invalid("invalid friend name")
        if (friends.containsKey(f.name)) return Result.Invalid("already a friend")
        if (!isValidColor(f.color)) return Result.Invalid("bad color")
        if (f.notes.length > MAX_NOTES || f.server.length > MAX_SERVER) {
            return Result.Invalid("notes or server over limit")
        }
        if (friends.size >= MAX_FRIENDS) return Result.Invalid("friend list full")
        friends[f.name] = f
        return Result.Ok(Unit)
    }

    fun remove(name: String): Boolean = friends.remove(name) != null

    fun rename(from: String, to: String): Result<Unit> {
        if (!isValidName(to)) return Result.Invalid("invalid friend name")
        if (friends.containsKey(to)) return Result.Invalid("name taken")
        val existing = friends.remove(from) ?: return Result.Invalid("no such friend")
        friends[to] = existing.copy(name = to)
        return Result.Ok(Unit)
    }

    fun setColor(name: String, color: String): Result<Unit> {
        if (!isValidColor(color)) return Result.Invalid("bad color")
        val existing = friends[name] ?: return Result.Invalid("no such friend")
        friends[name] = existing.copy(color = color)
        return Result.Ok(Unit)
    }

    fun clear() = friends.clear()

    /** Deterministic order for rendering: name, case-insensitive. */
    fun sorted(): List<Friend> = friends.values.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )

    fun serialize(): String {
        val arr = JSONArray()
        for (f in sorted()) {
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("color", f.color)
                    .put("notes", f.notes)
                    .put("server", f.server),
            )
        }
        return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("friends", arr).toString()
    }

    /**
     * Replaces the book from JSON. A malformed document is refused wholesale:
     * half-restoring a list is worse than leaving the previous one intact.
     */
    fun deserialize(json: String): Result<Int> {
        if (json.isBlank()) {
            friends.clear()
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
        val arr = root.optJSONArray("friends")
            ?: return Result.Invalid("missing friends array")
        if (arr.length() > MAX_FRIENDS) return Result.Invalid("too many friends")
        val staged = LinkedHashMap<String, Friend>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return Result.Invalid("entry $i is not an object")
            val f = Friend(
                name = o.optString("name"),
                color = o.optString("color", DEFAULT_COLOR),
                notes = o.optString("notes"),
                server = o.optString("server"),
            )
            if (!f.isSane()) return Result.Invalid("entry $i failed validation")
            if (staged.containsKey(f.name)) return Result.Invalid("entry $i duplicate name")
            staged[f.name] = f
        }
        friends.clear()
        friends.putAll(staged)
        return Result.Ok(friends.size)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_FRIENDS = 500
        const val MAX_NAME = 32
        const val MAX_NOTES = 500
        const val MAX_SERVER = 64
        const val DEFAULT_COLOR = "#4FD8C7"

        fun isValidName(name: String): Boolean =
            name.isNotEmpty() && name.length <= MAX_NAME

        fun isValidColor(color: String): Boolean {
            if (color.length != 7 || color[0] != '#') return false
            return color.drop(1).all { it in "0123456789abcdefABCDEF" }
        }
    }
}
