package dev.xykell.client.runtime.relay

/**
 * Block network-id resolution for the two addressing modes Bedrock uses.
 *
 * **The two modes.** In the start-game handshake a server tells us whether its
 * block network ids are *hashed* or *indexed*. In indexed mode a block's network
 * id is its ordinal position in the palette that shipped with the handshake. In
 * hashed mode it is a 32-bit hash of the block's definition instead, so two
 * servers that ship different palettes can still name the same block. A decoder
 * that assumes the wrong mode reads a hash as an ordinal: no error, no crash,
 * just every block resolving to a wrong definition -- which is why the mode is
 * read from the handshake rather than guessed.
 *
 * **What is clean-room here and what is not.** [fnv1a32] implements the
 * published FNV-1a 32-bit specification -- a public algorithm, not anyone's
 * protocol data. What we deliberately do **not** do is serialise a block
 * definition ourselves: the exact byte encoding a server hashes is a protocol
 * detail, and inventing one would produce hashes that never match, which looks
 * exactly like a broken mode. [hashOf] therefore takes the already-encoded bytes
 * from the caller. If we are not given the bytes, we say the hash is unknown
 * rather than returning a plausible wrong number.
 *
 * **Bounded by construction.** Palettes arrive from a server we do not control.
 * The definition count is capped, lookups on an unknown id return null instead
 * of throwing, and hashed lookups go through a map built once at registration
 * so registration is not quadratic in a server's own claims.
 *
 * Pure Kotlin: no Android imports, host-testable.
 */
class BlockPalette private constructor(
    /** True when ids are hashes rather than palette ordinals. */
    val hashed: Boolean,
    private val byIndex: List<Definition>,
    private val byHash: Map<Int, Definition>,
) {

    /** One palette entry. [name] is diagnostic only; identity is [id]. */
    data class Definition(val id: Int, val name: String, val version: String)

    val size: Int get() = byIndex.size

    /**
     * Resolves a network id in whichever mode this palette was built with.
     * Returns null for an unknown id -- a palette is a partial view, and "this
     * block was not in the handshake" is a normal answer, not an error.
     */
    fun resolve(networkId: Int): Definition? =
        if (hashed) byHash[networkId] else byIndex.getOrNull(networkId)

    /** Names in palette order, for the block-list UI. Bounded by construction. */
    fun names(): List<String> = byIndex.map { it.name }

    /** Ids in palette order; useful for diffing two handshakes. */
    fun ids(): List<Int> = byIndex.map { it.id }

    companion object {

        /** Cap on palette entries; see class docs. */
        const val MAX_DEFINITIONS = 65536

        /** FNV-1a 32-bit offset basis. Published algorithm constant. */
        const val FNV1_32_INIT = 0x811c9dc5.toInt()

        /** FNV-1a 32-bit prime. Published algorithm constant. */
        const val FNV1_PRIME_32 = 0x01000193

        /**
         * FNV-1a, 32-bit. The published algorithm, implemented from its
         * specification.
         *
         * [bytes] is taken as already-encoded: the encoding itself is the
         * server's business, not ours. Null input yields null rather than a
         * hash of nothing, so "no bytes" cannot masquerade as "hash of empty".
         */
        fun fnv1a32(bytes: ByteArray?): Int? {
            if (bytes == null) return null
            var h = FNV1_32_INIT
            for (b in bytes) {
                h = h xor (b.toInt() and 0xff)
                h *= FNV1_PRIME_32
            }
            return h
        }

        /** Hashes a definition from its caller-supplied encoding. */
        fun hashOf(bytes: ByteArray?): Int? = fnv1a32(bytes)

        /**
         * Builds an **indexed** palette. [definitions] is truncated to
         * [MAX_DEFINITIONS]; a longer list is a lie about what we will serve
         * later, so it is clamped and reported by [size].
         */
        fun indexed(definitions: List<Definition>): BlockPalette {
            val capped = definitions.take(MAX_DEFINITIONS)
            return BlockPalette(hashed = false, byIndex = capped, byHash = emptyMap())
        }

        /**
         * Builds a **hashed** palette from pre-encoded definition bytes.
         *
         * Each entry is `(definition, encodedBytes)`. An entry whose bytes are
         * absent is **kept** but unresolvable, because dropping it would hide
         * the fact that the palette is incomplete. Duplicates are kept; the
         * first wins the lookup, which is deterministic.
         */
        fun hashed(entries: List<Pair<Definition, ByteArray?>>): BlockPalette {
            val capped = entries.take(MAX_DEFINITIONS)
            val map = LinkedHashMap<Int, Definition>()
            for ((def, bytes) in capped) {
                val h = hashOf(bytes) ?: continue
                if (!map.containsKey(h)) map[h] = def
            }
            return BlockPalette(hashed = true, byIndex = capped.map { it.first }, byHash = map)
        }

        /** The empty palette: every lookup is null, and that is reported. */
        fun empty(hashed: Boolean = false): BlockPalette =
            BlockPalette(hashed, emptyList(), emptyMap())
    }
}