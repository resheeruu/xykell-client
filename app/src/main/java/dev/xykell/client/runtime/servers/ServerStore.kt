package dev.xykell.client.runtime.servers

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

data class ServerEntry(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val favorite: Boolean = false,
    val notes: String = "",
    val lastUsed: Long = 0L,
    val createdAt: Long = 0L
) {
    val displayAddress: String
        get() = if (port == ServerStore.DEFAULT_PORT) address else "$address:$port"
}

/** Local server book. Pure JVM: validation, JSON persistence with
 *  schema tolerance (unknown fields ignored, corrupt entries
 *  dropped — never a crash), sorting, search, and a read-only TCP
 *  reachability probe. The probe opens a TCP connection only —
 *  no game data is sent or parsed, no auth is touched. */
object ServerStore {
    const val DEFAULT_PORT = 19132
    const val MAX_NAME = 64
    const val MAX_NOTES = 500
    const val MAX_ADDRESS = 253

    enum class ProbeResult { OPEN, CLOSED, TIMEOUT, INVALID }

    /** null = valid; non-null = human-readable rejection reason. */
    fun validate(name: String, address: String, port: Int): String? {
        val n = name.trim()
        if (n.isEmpty()) return "empty name"
        if (n.length > MAX_NAME) return "name over $MAX_NAME chars"
        val a = address.trim()
        if (a.isEmpty()) return "empty address"
        if (a.length > MAX_ADDRESS) return "address over $MAX_ADDRESS chars"
        if (a.contains("://") || a.contains("/") || a.contains("@") ||
            a.contains(" ") || a.contains("#")
        ) return "address must be host or IP only"
        if (!a.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ':' }) {
            return "address has invalid characters"
        }
        if (port !in 1..65535) return "port must be 1-65535"
        return null
    }

    /** Blocking TCP connect with timeout. Runs off the UI thread. */
    fun probe(address: String, port: Int, timeoutMs: Int = 3000): ProbeResult {
        if (validate("probe", address, port) != null) return ProbeResult.INVALID
        return try {
            Socket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(address.trim(), port), timeoutMs)
                ProbeResult.OPEN
            }
        } catch (e: SocketTimeoutException) {
            ProbeResult.TIMEOUT
        } catch (e: IllegalArgumentException) {
            ProbeResult.INVALID
        } catch (e: Exception) {
            ProbeResult.CLOSED
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun toJson(entries: List<ServerEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject()
                .put("id", e.id)
                .put("name", e.name)
                .put("address", e.address)
                .put("port", e.port)
                .put("favorite", e.favorite)
                .put("notes", e.notes)
                .put("lastUsed", e.lastUsed)
                .put("createdAt", e.createdAt))
        }
        return JSONObject().put("version", 1).put("servers", arr).toString()
    }

    /** Tolerant decode: bad version shape still reads the array;
     ivalid entries are skipped, not fatal. */
    fun fromJson(json: String): List<ServerEntry> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("servers") ?: return emptyList()
        val out = mutableListOf<ServerEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            val address = o.optString("address", "").trim()
            val port = o.optInt("port", DEFAULT_PORT)
            if (validate(name, address, port) != null) continue
            out += ServerEntry(
                id = o.optString("id", "").ifEmpty { newId() },
                name = name,
                address = address,
                port = port,
                favorite = o.optBoolean("favorite", false),
                notes = o.optString("notes", "").take(MAX_NOTES),
                lastUsed = o.optLong("lastUsed", 0L),
                createdAt = o.optLong("createdAt", 0L)
            )
        }
        return out
    }

    /** Favorites first, then most recently used, then name. */
    fun sort(entries: List<ServerEntry>): List<ServerEntry> =
        entries.sortedWith(
            compareByDescending<ServerEntry> { it.favorite }
                .thenByDescending { it.lastUsed }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )

    /** Case-insensitive substring match over name/address/notes. */
    fun filter(entries: List<ServerEntry>, query: String): List<ServerEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.name.lowercase().contains(q) ||
                it.address.lowercase().contains(q) ||
                it.notes.lowercase().contains(q)
        }
    }
}
