package dev.xykell.client.runtime.relay

/**
 * Reads a block palette out of a start-game handshake.
 *
 * **Why this exists.** [BlockPalette] had no producer: the type existed, it was
 * tested, and nothing constructed one, because no code path read a palette out
 * of the handshake. That is the [RESEARCH REQUIRED] marker it carried. This is
 * that producer.
 *
 * **Format and its provenance.** The envelope below is the publicly documented
 * Bedrock shape: an unsigned varint palette version, then a varint entry
 * count, then that many entries. Entries are read as **caller-supplied
 * pre-decoded definitions** rather than by parsing the palette's own encoding
 * here, and that is deliberate:
 *
 *  - The exact per-entry encoding is version-specific. Inventing one would
 *    produce palettes that parse cleanly and name the wrong blocks, which looks
 *    identical to a correct decode until a block renders as something else.
 *  - What this type is responsible for — and what it is honest about — is the
 *    envelope, the count, the bounds, and the hashed/indexed selection. Those
 *    are the parts that are the same regardless of entry encoding.
 *
 * So [read] takes a [DefinitionSource], which knows how to turn entry *n* into a
 * definition and (for hashed mode) its encoded bytes. A caller with that
 * decoder supplies one; a caller without gets a refusal, not a guess.
 *
 * **Refusals are the point.** An unknown palette version, an implausible count,
 * or a payload that runs out mid-envelope all refuse. A palette that is
 * short, oversized or of an unknown shape yields no palette rather than a
 * partial one, because a partial palette resolves later ids to the wrong
 * definition with no error at all — the exact failure mode [BlockPalette] exists
 * to prevent.
 *
 * Pure Kotlin: no Android imports, host-testable.
 */
object BlockPaletteReader {

    /**
     * Produces the nth entry of a palette, or null when *n* is out of range.
     *
     * [bytes] is the entry's own already-encoded form and is what hashed mode
     * hashes; null there means "this entry cannot be resolved by hash", which
     * is different from "this entry does not exist".
     */
    fun interface DefinitionSource {
        fun entry(index: Int): Entry?
    }

    data class Entry(
        val definition: BlockPalette.Definition,
        val encodedBytes: ByteArray?,
    )

    /** Why a palette payload was refused. */
    enum class Refusal {
        EMPTY_PAYLOAD,
        UNSUPPORTED_VERSION,
        TRUNCATED_ENVELOPE,
        COUNT_OUT_OF_RANGE,
        TOO_FEW_ENTRIES,
        ENCODER_FAILED,
    }

    sealed class Result {
        data class Ok(val palette: BlockPalette) : Result()
        data class Refused(val reason: Refusal) : Result()
    }

    /**
     * Palette versions this reader understands.
     *
     * Listed rather than "accept any". The whole failure mode being avoided is
     * silently mis-reading a shape we do not recognise, so an unknown version
     * is refused loudly instead of parsed hopefully.
     */
    val SUPPORTED_VERSIONS = setOf(1)

    /**
     * Parses the envelope and builds a palette from [source].
     *
     * @param hashed true when the handshake reported hashed network ids.
     */
    fun read(
        payload: ByteArray,
        hashed: Boolean,
        source: DefinitionSource,
        maxEntries: Int = BlockPalette.MAX_DEFINITIONS,
        limits: DecodeLimits.Limits = DecodeLimits.DEFAULT,
    ): Result {
        if (payload.isEmpty()) return Result.Refused(Refusal.EMPTY_PAYLOAD)

        var i = 0

        // palette version
        val version = readVarUInt(payload, i) ?: return Result.Refused(Refusal.TRUNCATED_ENVELOPE)
        i = version.second
        if (!SUPPORTED_VERSIONS.contains(version.first)) {
            return Result.Refused(Refusal.UNSUPPORTED_VERSION)
        }

        // entry count
        val count = readVarUInt(payload, i) ?: return Result.Refused(Refusal.TRUNCATED_ENVELOPE)
        i = count.second
        val n = count.first
        if (n <= 0) return Result.Refused(Refusal.COUNT_OUT_OF_RANGE)

        val listBound = DecodeLimits.checkListSize(n, limits)
        if (!listBound.ok) return Result.Refused(Refusal.COUNT_OUT_OF_RANGE)
        if (n > maxEntries) return Result.Refused(Refusal.COUNT_OUT_OF_RANGE)

        val entries = ArrayList<Entry>(n)
        for (index in 0 until n) {
            val e = try {
                source.entry(index)
            } catch (ex: Exception) {
                // An encoder that throws is a failure to produce a palette, not
                // a palette with a hole in it.
                return Result.Refused(Refusal.ENCODER_FAILED)
            } ?: return Result.Refused(Refusal.TOO_FEW_ENTRIES)
            entries.add(e)
        }

        val palette = if (hashed) {
            BlockPalette.hashed(entries.map { it.definition to it.encodedBytes })
        } else {
            BlockPalette.indexed(entries.map { it.definition })
        }
        return Result.Ok(palette)
    }

    /** Builds a palette from a list already decoded elsewhere. */
    fun fromEntries(
        entries: List<Entry>,
        hashed: Boolean,
        maxEntries: Int = BlockPalette.MAX_DEFINITIONS,
    ): Result {
        if (entries.isEmpty()) return Result.Refused(Refusal.TOO_FEW_ENTRIES)
        if (entries.size > maxEntries) return Result.Refused(Refusal.COUNT_OUT_OF_RANGE)
        val palette = if (hashed) {
            BlockPalette.hashed(entries.map { it.definition to it.encodedBytes })
        } else {
            BlockPalette.indexed(entries.map { it.definition })
        }
        return Result.Ok(palette)
    }

    /** Unsigned LEB128, little-endian base-128. Null on truncation or overflow. */
    private fun readVarUInt(src: ByteArray, from: Int): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var i = from
        while (true) {
            if (i >= src.size) return null
            val b = src[i].toInt() and 0xff
            i++
            value = value or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) return value to i
            shift += 7
            // More than five bytes cannot fit in an Int; refusing here stops a
            // long stream of continuation bytes from spinning.
            if (shift > 28) return null
        }
    }
}