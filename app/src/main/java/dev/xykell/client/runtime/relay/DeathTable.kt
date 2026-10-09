package dev.xykell.client.runtime.relay

/**
 * Deaths seen this session, from clientbound `DeathInfo 0xbd`.
 *
 * That packet is one string (the cause) and a string array, with no enum, no
 * opaque field and no framing guess in it -- which is why this exists while
 * several other "just decode one more packet" plans do not.
 *
 * The death *position* is the local player's own last reported position at the
 * moment the death notice arrived. That is the player's own outbound
 * MovePlayer 0x13, never a server-supplied one: trusting a server-supplied
 * position for "where am I" is how a distance check gets defeated, and the same
 * reasoning applies to where the player died.
 */
class DeathTable(private val maxDeaths: Int = MAX_DEATHS) {

    data class Death(
        val cause: String,
        val x: Float,
        val y: Float,
        val z: Float,
        /** Monotonic ms from the context clock at which the notice arrived. */
        val observedAtMs: Long,
    )

    private val deaths = ArrayDeque<Death>()

    /** The most recent death, or null if none has been observed. */
    fun latest(): Death? = deaths.lastOrNull()

    /** Most recent first, bounded. */
    fun recent(): List<Death> = deaths.toList().asReversed()

    val size: Int get() = deaths.size

    fun clear() = deaths.clear()

    /**
     * Feed one packet. Returns the recorded death, or null when the packet was
     * not a death notice this table could read.
     */
    fun observe(
        raw: ByteArray,
        selfX: Float,
        selfY: Float,
        selfZ: Float,
        observedAtMs: Long,
    ): Death? {
        val cause = readDeathCause(raw) ?: return null
        val death = Death(cause, selfX, selfY, selfZ, observedAtMs)
        deaths.addLast(death)
        while (deaths.size > maxDeaths) deaths.removeFirst()
        return death
    }

    private class Reader(private val b: ByteArray) {
        var p = 0

        /**
         * The packet id, read as a LEB128 varuint.
         *
         * Not `raw[0] & 0xff`: DeathInfo is 0xbd, whose high bit is set, so its
         * header really is two bytes (bd 01) and masking the first byte happens
         * to look right while disagreeing with the framing. The same class of
         * one-byte-assumption is what made the old knockback id drift.
         */
        fun headerId(): Int? {
            var result = 0
            var shift = 0
            var i = p
            while (i < b.size && shift <= 28) {
                val byte = b[i].toInt() and 0xff
                result = result or ((byte and 0x7f) shl shift)
                i++
                if (byte and 0x80 == 0) {
                    p = i
                    return result
                }
                shift += 7
            }
            return null
        }

        fun varString(): String? {
            var shift = 0
            var value = 0
            var i = p
            while (i < b.size && shift <= 28) {
                val byte = b[i].toInt() and 0xff
                value = value or ((byte and 0x7f) shl shift)
                i++
                if (byte and 0x80 == 0) {
                    // The cause is a short enum-ish string; a longer claim is a
                    // corrupted length, not a message to allocate from.
                    if (value <= MAX_CAUSE_BYTES && i + value <= b.size) {
                        p = i + value
                        return String(b, i, value, Charsets.UTF_8)
                    }
                    return null
                }
                shift += 7
            }
            return null
        }
    }

    companion object {
        /** DeathInfo 0xbd. */
        const val ID_DEATH_INFO = 0xbd

        /** Enough to show a scrollback without letting a session grow forever. */
        const val MAX_DEATHS = 16

        private const val MAX_CAUSE_BYTES = 128

        /** The cause string, or null when the packet is not a readable death. */
        fun readDeathCause(raw: ByteArray): String? {
            if (raw.isEmpty()) return null
            val c = Reader(raw)
            if (c.headerId() != ID_DEATH_INFO) return null
            val cause = c.varString() ?: return null
            // An empty cause is not a message: it would render a death with no
            // reason, which reads as a bug in the client rather than in the game.
            return cause.ifEmpty { null }
        }
    }
}