package dev.xykell.client.runtime.relay

/**
 * The online roster, decoded from clientbound `PlayerList 0x3f`.
 *
 * This is the one packet that answers "who is in this world right now", which
 * is what a tab list shows and what a join/leave alert is triggered by. The
 * decoder stops after the fields it needs: the rest of an entry (skin data,
 * device input flags, the title blob) is skipped rather than guessed at, so a
 * version that grows the struct cannot make this read the wrong bytes.
 *
 * Add and remove are keyed on the 16-byte UUID, never on the name: a player can
 * change their name mid-session, and a roster keyed by name would then carry two
 * entries for one person and fail to remove either.
 */
class PlayerListTable(private val maxPlayers: Int = MAX_PLAYERS) {

    data class Entry(val uuid: String, val name: String)

    /**
     * What one packet actually changed.
     *
     * Returning the change rather than a boolean is what lets the caller feed an
     * observation: a bare "applied" gives no way to learn *who* joined, which is
     * the entire content of a join alert.
     */
    sealed interface Change {
        data class Added(val uuid: String, val name: String) : Change
        data class Removed(val uuid: String) : Change
        data object Cleared : Change
    }

    private val roster = LinkedHashMap<String, Entry>()

    val size: Int get() = roster.size

    fun all(): List<Entry> = roster.values.toList()

    fun names(): List<String> = roster.values.map { it.name }

    fun get(uuid: String): Entry? = roster[uuid]

    fun clear() = roster.clear()

    /**
     * Feed one packet. Returns true when it was a PlayerList entry this table
     * applied. Anything malformed is ignored rather than throwing: the relay's
     * forwarding does not depend on this table being right.
     */
    fun observe(raw: ByteArray): Change? {
        if (raw.size < 2) return null
        val c = Reader(raw)
        c.skipHeader()
        return when (c.u8()) {
            TYPE_ADD -> {
                val uuid = c.uuid() ?: return null
                val name = c.varString() ?: return null
                // Re-adding an existing uuid replaces it: a rename is one
                // player, not two, and a name-keyed roster would leak the old.
                roster.remove(uuid)
                roster[uuid] = Entry(uuid, name)
                while (roster.size > maxPlayers) {
                    val oldest = roster.keys.firstOrNull() ?: break
                    roster.remove(oldest)
                }
                Change.Added(uuid, name)
            }
            TYPE_REMOVE -> {
                val uuid = c.uuid() ?: return null
                roster.remove(uuid)
                Change.Removed(uuid)
            }
            // Type 2 clears the list in some builds. Ignoring it would leave a
            // stale roster on screen claiming players who have left.
            TYPE_CLEAR -> {
                roster.clear()
                Change.Cleared
            }
            else -> null
        }
    }

    private class Reader(private val b: ByteArray) {
        var p = 0

        /** Step over the header varint, which is one byte for every id < 0x80. */
        fun skipHeader() {
            while (p < b.size && (b[p].toInt() and 0x80) != 0) p++
            if (p < b.size) p++
        }

        fun u8(): Int {
            if (p >= b.size) return -1
            return b[p++].toInt() and 0xff
        }

        /** 16 raw UUID bytes as lowercase hex -- stable, order-independent. */
        fun uuid(): String? {
            if (p + 16 > b.size) return null
            val out = StringBuilder(32)
            for (i in 0 until 16) {
                out.append(String.format("%02x", b[p + i].toInt() and 0xff))
            }
            p += 16
            return out.toString()
        }

        fun varString(): String? {
            if (p >= b.size) return null
            var shift = 0
            var value = 0
            var i = p
            while (i < b.size && shift <= 28) {
                val byte = b[i].toInt() and 0xff
                value = value or ((byte and 0x7f) shl shift)
                i++
                if (byte and 0x80 == 0) {
                    // Refuse an absurd length instead of allocating from a
                    // corrupted varint.
                    if (value < 0 || value > MAX_NAME_BYTES) return null
                    if (i + value > b.size) return null
                    p = i + value
                    return String(b, i, value, Charsets.UTF_8)
                }
                shift += 7
            }
            return null
        }
    }

    companion object {
        const val MAX_PLAYERS = 512
        private const val MAX_NAME_BYTES = 64

        /** PlayerList 0x3f entry types. */
        private const val TYPE_ADD = 0
        private const val TYPE_REMOVE = 1
        private const val TYPE_CLEAR = 2
    }
}