package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.RelayDirection
/**
 * WORLD registry batch: derived views and relay-packet transforms for
 * `xykell.world.*`.
 *
 * The old note here said every WORLD id was impossible because "a relay has no
 * renderer and no world model to overlay". The second half of that is no longer
 * true: UpdateBlock 0x15 and LevelChunk 0x3A are decodable, and ModuleContext
 * carries the live entity table and the self position. So the five ids whose
 * honest job is *choosing what to draw* — block_esp, block_tracer,
 * chunk_borders, chunk_finder, new_chunks — now do that work and are in
 * [IMPLEMENTED]. The decoders they share live in [VisualModules] because the
 * packet layouts are identical; only the registry category differs.
 *
 * The remaining nine are still impossible, and the reasons are now narrower and
 * factual: they need block identity this repo cannot name (ore_esp,
 * spawner_protect), the decoded sub-chunk array (hole_esp), or they must
 * *originate* action packets rather than rewrite them (scaffold, nuker,
 * fast_break, fast_place, schematic). xray remains a render-pass change.
 *
 * None of the implemented ids rewrites bytes: each one's output is a plain data
 * structure an overlay draws, so [transform] forwards every packet untouched.
 * That is the same shape as the visual ESP ids, and it is not a stub — the
 * behaviour is in [blockMarkers], [blockTracers], [chunkMarks],
 * [chunkBorderLines] and [newChunkMarks].
 */
object WorldModules {

    /** Ids with real behaviour. */
    val IMPLEMENTED: Set<String> = setOf(
        "xykell.world.block_esp",
        "xykell.world.block_tracer",
        "xykell.world.chunk_borders",
        "xykell.world.chunk_finder",
        "xykell.world.new_chunks",
    )

    /** Ids a relay genuinely cannot deliver, with the reason. */
    val IMPOSSIBLE: Map<String, String> = mapOf(
        "xykell.world.scaffold" to
            "scaffold must place blocks under itself, so it has to *originate* placement " +
            "actions; a relay can rewrite or drop the packets the client sends but cannot " +
            "author a well-formed InventoryTransaction 0x1E, whose Transaction layout is not " +
            "decoded here, and it has no block data to know what to fill.",
        "xykell.world.nuker" to
            "nuker breaks every block in reach by injecting a destroy action per block; the " +
            "relay holds no block array to pick targets from, and PlayerAction 0x24 carries " +
            "only the position the client aimed at, one action at a time.",
        "xykell.world.fast_break" to
            "break progress is accumulated server-side and only the client's own break timer " +
            "shortens it; PlayerAction 0x24's action field selects start/stop/destroy, and " +
            "rewriting it does not shorten the server's accumulated progress, so the rewrite " +
            "would desync rather than speed anything up.",
        "xykell.world.fast_place" to
            "the use-tick cooldown before the next placement is client input state, not a " +
            "field of any decoded packet; the relay sees the action after the client already " +
            "decided to send it.",
        "xykell.world.spawner_protect" to
            "protecting a spawner means identifying it from the block at the action's " +
            "position, and UpdateBlock 0x15 carries a numeric block_runtime_id with no " +
            "runtime-id-to-name palette anywhere in this repo, so no spawner is ever " +
            "recognisable here; dropping every destroy action would be a different module.",
        "xykell.world.xray" to
            "xray is a renderer state change (culling non-target blocks) applied inside the " +
            "client; decoding that a block changed (UpdateBlock 0x15) is possible, changing " +
            "whether the renderer draws it is not reachable from a relay.",
        "xykell.world.ore_esp" to
            "ore ESP needs to tell an ore block from stone, and the only ore signal on the " +
            "wire is a numeric block_runtime_id; the palette that would name it lives inside " +
            "LevelChunk 0x3A's opaque payload and StartGame 0x0B's undecodable block_properties.",
        "xykell.world.hole_esp" to
            "a hole is an air pocket inside the decoded sub-chunk block array; chunk data " +
            "reaches this repo only as LevelChunk 0x3A's opaque payload, and UpdateSubchunkBlocks " +
            "0xAC's BlockUpdate entry layout is not defined in the vendored proto, so there is " +
            "no block scan to run.",
        "xykell.world.schematic" to
            "schematic reads chunk block data and replays it as placement actions; both halves " +
            "are missing — the chunk block decode (see hole_esp) and the action encode (see " +
            "scaffold).",
    )

    /**
     * Every WORLD id either has no byte-level rewrite of its own or is not in
     * [IMPLEMENTED], so packets are forwarded untouched. The implemented ids'
     * behaviour is the derived views below.
     */
    fun transform(
        id: String,
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext = ModuleContext(),
    ): List<ByteArray> =
        listOf(packet) // derived-view ids have no bytes to rewrite

    // ------------------------------------------------------------ derived views

    /** `block_esp`: the block UpdateBlock 0x15 changed, within [maxDistance]. */
    fun blockMarkers(ctx: ModuleContext, packet: ByteArray, maxDistance: Float) =
        VisualModules.blockMarkers(ctx, packet, maxDistance)

    /** `block_tracer`: the same block change as a line out of the player. */
    fun blockTracers(ctx: ModuleContext, packet: ByteArray, maxDistance: Float) =
        VisualModules.blockTracers(ctx, packet, maxDistance)

    /** `chunk_finder`: where the chunk in this LevelChunk 0x3A sits. */
    fun chunkMarks(ctx: ModuleContext, packet: ByteArray, maxDistance: Float) =
        VisualModules.chunkMarks(ctx, packet, maxDistance)

    /** `chunk_borders`: the four world-space edges of that chunk. */
    fun chunkBorderLines(ctx: ModuleContext, packet: ByteArray) =
        VisualModules.chunkBorderLines(ctx, packet)

    /** `new_chunks`: that chunk, when it arrived from outside the view radius. */
    fun newChunkMarks(ctx: ModuleContext, packet: ByteArray) =
        VisualModules.newChunkMarks(ctx, packet)
}