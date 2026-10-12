package dev.xykell.client.runtime.relay

/**
 * Decodes a block palette out of a StartGame payload, for the protocol versions
 * whose schema actually transmits one.
 *
 * **Scope, stated plainly.** Of the 50 Bedrock schemas this project vendors,
 * **2** declare `block_palette` inside StartGame (1.16.201, 1.16.210). The other
 * 48 carry either `block_properties` -- a name to NBT-state list holding no
 * runtime ids -- or, on 1.21+, nothing but a `block_network_ids_are_hashes`
 * flag, meaning runtime ids are opaque hashes with no palette on the wire at
 * all. There is no palette to read in those versions. This decoder refuses them
 * with the recorded reason instead of inventing one, because a palette that
 * parses cleanly and names the wrong blocks looks exactly like a correct decode
 * until something renders wrong.
 *
 * **Why a generated plan.** The palette sits at field 53 of 57 in 1.16.201 and
 * 55 of 59 in 1.16.210, with two fields inserted between those versions. A
 * hand-written offset would be right for one and silently misaligned for the
 * other. [StartGamePalettePlan] is derived from the vendored schema, so the
 * walk order is a fact about the protocol rather than an assumption about it.
 *
 * **What it skips.** Everything before the palette is stepped over, not
 * interpreted: the caller wants the palette, not a StartGame object. Only the
 * palette entries' `name` strings are kept. Each entry's `state` is a
 * little-endian NBT compound, so an NBT skipper is required to step over it --
 * an NBT payload whose length is not known in advance cannot be skipped by
 * arithmetic.
 *
 * Pure Kotlin: no Android imports, host-testable.
 */
object StartGamePaletteDecoder {

    /** One decoded palette entry: its block name and its raw state bytes. */
    data class Entry(val name: String, val state: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Entry && name == other.name && state.contentEquals(other.state))

        override fun hashCode(): Int = 31 * name.hashCode() + state.contentHashCode()
    }

    /** Why a payload was not decoded. Distinct claims, deliberately not merged. */
    enum class Refusal {
        /** Empty payload. */
        EMPTY_PAYLOAD,

        /** Version was never vendored, so we cannot describe its protocol. */
        UNKNOWN_VERSION,

        /** Version was vendored but provably has no palette in the handshake. */
        NO_PALETTE_IN_HANDSHAKE,

        /** Ran out of bytes before or inside the palette. */
        TRUNCATED,

        /** A byte did not fit the declared type (bad varint, bad UTF-8, etc). */
        MALFORMED,

        /** Palette entry count was 0 or beyond the ceiling. */
        COUNT_OUT_OF_RANGE,
    }

    sealed class Result {
        data class Ok(val version: String, val entries: List<Entry>) : Result()

        /**
         * [reason] plus a human-readable explanation. For
         * [Refusal.NO_PALETTE_IN_HANDSHAKE] and [Refusal.UNKNOWN_VERSION] the
         * detail is the protocol fact, so it is surfaced rather than logged and
         * dropped -- a caller needs to know *why* a version has no palette.
         */
        data class Refused(val reason: Refusal, val detail: String) : Result()
    }

    private const val MAX_ENTRIES = BlockPalette.MAX_DEFINITIONS

    /** Cursor over a payload; every read advances it or throws [Malformed]. */
    private class Cur(val buf: ByteArray) {
        var i = 0

        fun byte(): Int {
            if (i >= buf.size) throw Truncated
            return buf[i++].toInt() and 0xff
        }

        fun take(n: Int): ByteArray {
            if (n < 0 || i + n > buf.size) throw Truncated
            val out = buf.copyOfRange(i, i + n)
            i += n
            return out
        }

        fun skip(n: Int) {
            if (n < 0 || i + n > buf.size) throw Truncated
            i += n
        }

        /** Unsigned LEB128. Throws rather than overflowing silently. */
        fun varUInt(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val b = byte()
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
                if (shift > 63) throw Malformed
            }
        }

        /** ZigZag-decoded signed LEB128. */
        fun zigzag(): Long {
            val v = varUInt()
            return (v ushr 1) xor -(v and 1L)
        }

        /** Varint length-prefixed UTF-8. */
        fun string(): String {
            val n = varUInt()
            if (n > Int.MAX_VALUE) throw Malformed
            val bytes = take(n.toInt())
            val s = String(bytes, Charsets.UTF_8)
            // A length that does not decode is a byte range that was never a
            // string; without this a truncated payload yields a palette whose
            // block names are silently wrong.
            if (s.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return s
            throw Malformed
        }

        fun f32Skip() = skip(4)
    }

    private object Truncated : RuntimeException("truncated payload") {
        private fun readResolve(): Any = Truncated
    }

    private object Malformed : RuntimeException("malformed payload") {
        private fun readResolve(): Any = Malformed
    }

    /**
     * Decodes the palette from a StartGame [payload].
     *
     * @param version the negotiated protocol version, e.g. "1.16.201".
     */
    fun decode(version: String, payload: ByteArray): Result {
        if (payload.isEmpty()) {
            return Result.Refused(Refusal.EMPTY_PAYLOAD, "empty payload")
        }
        val plan = StartGamePalettePlan.PLANS[version]
        if (plan == null) {
            val reason = StartGamePalettePlan.REFUSED[version]
                ?: "version $version was never vendored, so its protocol is unknown"
            val why = if (reason.startsWith("StartGame")) Refusal.NO_PALETTE_IN_HANDSHAKE else Refusal.UNKNOWN_VERSION
            return Result.Refused(why, reason)
        }

        // A relayed packet starts with its id byte, so the walk begins after it.
        // Checking it against the table means a payload handed in under the
        // wrong version is refused here rather than silently walked as though it
        // were the right packet.
        val expectedId = BedrockPacketIds.idForVersion(version, "StartGame")
            ?: return Result.Refused(
                Refusal.UNKNOWN_VERSION,
                "no StartGame id for $version",
            )
        if ((payload[0].toInt() and 0xff) != expectedId) {
            return Result.Refused(
                Refusal.MALFORMED,
                "packet id 0x${(payload[0].toInt() and 0xff).toString(16)} is not StartGame " +
                    "(0x${expectedId.toString(16)}) on $version",
            )
        }

        val cur = Cur(payload)
        return try {
            cur.skip(1) // the id byte checked above
            for (op in plan) step(cur, op)
            readPalette(version, cur)
        } catch (t: Truncated) {
            Result.Refused(Refusal.TRUNCATED, "payload ended inside StartGame")
        } catch (m: Malformed) {
            Result.Refused(Refusal.MALFORMED, "a field did not match its declared type")
        }
    }

    private fun step(c: Cur, op: StartGamePalettePlan.Op) {
        when (op) {
            StartGamePalettePlan.Op.BOOL -> c.byte()
            StartGamePalettePlan.Op.LI16 -> c.skip(2)
            StartGamePalettePlan.Op.LI32 -> c.skip(4)
            StartGamePalettePlan.Op.LI64 -> c.skip(8)
            StartGamePalettePlan.Op.LF32 -> c.skip(4)
            StartGamePalettePlan.Op.STRING -> c.string()
            StartGamePalettePlan.Op.VARINT, StartGamePalettePlan.Op.VARINT64 -> c.varUInt()
            StartGamePalettePlan.Op.ZIGZAG32, StartGamePalettePlan.Op.ZIGZAG64 -> c.zigzag()
            StartGamePalettePlan.Op.EXPERIMENTS -> {
                // li32 count, then (string, bool) per experiment.
                val n = le32(c.take(4))
                repeat(n) {
                    c.string()
                    c.byte()
                }
            }
            StartGamePalettePlan.Op.GAME_RULES -> {
                // varint count, then (string, varint kind, value). The schema's
                // switch names three kinds of the same width on the wire, but it
                // names them by discriminator, so the discriminator is honoured
                // and an unrecognised kind refuses rather than guessing.
                val n = c.varUInt().toCount()
                repeat(n) {
                    c.string()
                    when (c.varUInt()) {
                        1L -> c.byte() // bool
                        2L -> c.zigzag() // int
                        3L -> c.skip(4) // float
                        else -> throw Malformed
                    }
                }
            }
        }
    }

    private fun readPalette(version: String, c: Cur): Result {
        val count = c.varUInt()
        if (count == 0L || count > MAX_ENTRIES) {
            return Result.Refused(
                Refusal.COUNT_OUT_OF_RANGE,
                "palette claimed $count entries (limit $MAX_ENTRIES)",
            )
        }
        val n = count.toInt()
        val entries = ArrayList<Entry>(n)
        repeat(n) {
            val name = c.string()
            val state = readNbt(c)
            entries.add(Entry(name, state))
        }
        return Result.Ok(version, entries)
    }

    private fun le32(b: ByteArray): Int =
        (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)

    /**
     * Little-endian NBT, returned whole. Bedrock NBT is little-endian with
     * network varint lengths in its list/array headers, which is why [Cur] is
     * reused here rather than assuming Java's DataInput layout.
     */
    /**
     * One complete tagged NBT value, returned as the raw bytes it occupied.
     *
     * Bedrock NBT is little-endian with network varint lengths in its string,
     * list and array headers, so the payload length is not known in advance and
     * cannot be skipped arithmetically -- hence a real walker. Capturing the
     * bytes is what lets a palette entry keep its `state` for a caller that
     * wants it, rather than forcing it to be thrown away.
     */
    private fun readNbt(c: Cur): ByteArray {
        val start = c.i
        val tag = c.byte()
        skipPayload(c, tag)
        return c.buf.copyOfRange(start, c.i)
    }

    /** Skips one TAG_End-terminated compound's entries, without its own tag. */
    private fun skipCompound(c: Cur) {
        while (true) {
            val tag = c.byte()
            if (tag == TAG_END) return
            c.string() // entry name
            skipPayload(c, tag)
        }
    }

    /** Skips the payload of an already-read tag byte. */
    private fun skipPayload(c: Cur, tag: Int) {
        when (tag) {
            TAG_END -> Unit
            TAG_BYTE -> c.byte()
            TAG_SHORT -> c.skip(2)
            TAG_INT -> c.skip(4)
            TAG_LONG -> c.skip(8)
            TAG_FLOAT -> c.skip(4)
            TAG_DOUBLE -> c.skip(8)
            TAG_BYTE_ARRAY -> c.skip(c.varUInt().toCount() * 1)
            TAG_STRING -> c.skip(c.varUInt().toCount())
            TAG_LIST -> {
                val elem = c.byte()
                val n = c.varUInt().toCount()
                if (elem != TAG_END) repeat(n) { skipPayload(c, elem) }
            }
            TAG_COMPOUND -> skipCompound(c)
            TAG_INT_ARRAY -> c.skip(c.varUInt().toCount() * 4)
            TAG_LONG_ARRAY -> c.skip(c.varUInt().toCount() * 8)
            else -> throw Malformed
        }
    }

    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12

    /** A varint used as a count or length: refuse anything that cannot be a size. */
    private fun Long.toCount(): Int {
        if (this < 0 || this > Int.MAX_VALUE) throw Malformed
        return this.toInt()
    }
}
