package dev.xykell.client.runtime.relay

/**
 * Holds the block palette observed from a session's StartGame handshake.
 *
 * **This is the caller [StartGamePaletteDecoder] was written for.** The decoder
 * existed and was tested with no path that ever invoked it, so the palette it
 * produced could never reach a module; that is the gap this closes.
 *
 * **What it does not pretend.** Only 2 of the 50 vendored Bedrock schemas put a
 * palette in the handshake at all (1.16.201, 1.16.210). Every other version is
 * refused by the decoder with the reason recorded in [StartGamePalettePlan.REFUSED].
 * [state] keeps that refusal visible instead of collapsing it into "no palette",
 * so a caller can tell "this protocol has no palette" from "we never saw a
 * StartGame" from "we saw one and it did not decode". Those are different
 * claims and a module that renders blocks needs to know which one it is looking
 * at.
 *
 * Pure Kotlin: no Android imports, host-testable.
 */
class BlockPaletteTracker {

    /** Why the palette is what it is. */
    sealed class State {
        /** No StartGame has been seen yet. */
        object AwaitingStartGame : State()

        /** A palette was decoded and is usable. */
        data class Available(
            val version: String,
            val palette: BlockPalette,
            val entries: List<StartGamePaletteDecoder.Entry>,
        ) : State()

        /**
         * A StartGame was seen and produced no palette.
         *
         * [reason] is the decoder's verdict and [detail] says why, which for
         * most versions means the protocol does not transmit one.
         */
        data class Refused(
            val reason: StartGamePaletteDecoder.Refusal,
            val detail: String,
        ) : State()
    }

    var state: State = State.AwaitingStartGame
        private set

    /** True only when [state] holds a decoded palette. */
    val palette: BlockPalette?
        get() = (state as? State.Available)?.palette

    /** Id of StartGame in the negotiated version, or null if that version is unknown. */
    private fun startGameId(version: String): Int? = START_GAME_ID_BY_VERSION[version]

    /**
     * Offers one packet for the protocol *number* the handshake negotiated.
     *
     * This is the entry point the relay uses: Bedrock announces a number, not a
     * version string, so the version is resolved here. An unrecognised number
     * refuses as unknown rather than assuming the default schema.
     */
    fun offer(protocolNumber: Int, packet: ByteArray): Boolean {
        val version = StartGamePalettePlan.versionFor(protocolNumber)
        if (version == null) {
            if (packet.isEmpty()) return false
            val id = packet[0].toInt() and 0xff
            val isStartGame = BEDROCK_START_GAME_IDS.contains(id)
            if (isStartGame) {
                state = State.Refused(
                    StartGamePaletteDecoder.Refusal.UNKNOWN_VERSION,
                    "protocol $protocolNumber is not among the ${StartGamePalettePlan.PROTOCOL_VERSIONS.size} vendored schemas",
                )
            }
            return isStartGame
        }
        return offer(version, packet)
    }

    /**
     * StartGame ids across the vendored schemas. Used only to notice a StartGame
     * whose version is unknown -- otherwise an unrecognised protocol would look
     * identical to "no handshake seen".
     *
     * Resolved once per process in the companion below, not per instance and not
     * per id; see [START_GAME_ID_BY_VERSION] for what this used to cost.
     */
    private val BEDROCK_START_GAME_IDS: Set<Int> get() = ALL_START_GAME_IDS

    private companion object {
        /**
         * StartGame id per version string, resolved at most once per process.
         *
         * Both of the measurements below came from this class and both are fixed
         * here rather than by sprinkling caches at the call sites:
         *
         * - `BedrockPacketIds.idForVersion` copies the whole 244-entry table on
         *   every call, measured at 9.99us per packet. `offer` needs this per
         *   packet, so it ran once for every packet in every batch.
         * - the previous `BEDROCK_START_GAME_IDS` scanned all 256 possible ids
         *   against all 51 versions, calling `forVersion` for each pair. That is
         *   13,056 full table copies per tracker instance, measured at 277.5ms
         *   on the JVM, paid at construction time on whatever thread built it.
         *
         * The tables are immutable and the version list is fixed, so resolving
         * each version once and reusing the result is safe.
         */
        val START_GAME_ID_BY_VERSION: Map<String, Int> by lazy {
            BedrockPacketIds.versions.mapNotNull { version ->
                BedrockPacketIds.forVersion(version)
                    ?.get("StartGame")
                    ?.id
                    ?.let { version to it }
            }.toMap()
        }

        val ALL_START_GAME_IDS: Set<Int> by lazy { START_GAME_ID_BY_VERSION.values.toSet() }
    }

    /**
     * Offers one upstream (server-to-client) packet.
     *
     * Returns true when the packet was a StartGame for [version] and was
     * consumed, so the caller can record the attempt even when it refused.
     */
    fun offer(version: String, packet: ByteArray): Boolean {
        if (packet.isEmpty()) return false
        val id = startGameId(version) ?: return false
        if ((packet[0].toInt() and 0xff) != id) return false

        when (val decoded = StartGamePaletteDecoder.decode(version, packet)) {
            is StartGamePaletteDecoder.Result.Ok -> {
                // Each entry's bytes are what hashed mode hashes, so they are
                // kept; the name is the definition's identity.
                val entries = decoded.entries.map {
                    BlockPaletteReader.Entry(
                        BlockPalette.Definition(0, it.name, decoded.version),
                        it.state,
                    )
                }
                state = when (val built = BlockPaletteReader.fromEntries(entries, hashed = false)) {
                    is BlockPaletteReader.Result.Ok ->
                        State.Available(decoded.version, built.palette, decoded.entries)
                    // fromEntries only refuses an empty or oversized list, and
                    // decoded.entries is neither here; if that changes, say so
                    // rather than silently reporting the palette as decoded.
                    is BlockPaletteReader.Result.Refused ->
                        State.Refused(
                            StartGamePaletteDecoder.Refusal.COUNT_OUT_OF_RANGE,
                            built.reason.name,
                        )
                }
            }
            is StartGamePaletteDecoder.Result.Refused ->
                state = State.Refused(decoded.reason, decoded.detail)
        }
        return true
    }
}