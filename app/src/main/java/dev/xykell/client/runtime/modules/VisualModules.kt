package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.EntityTable
import dev.xykell.client.runtime.relay.RelayDirection
import kotlin.math.sqrt
/**
 * Visual-category module transforms and derived views for the relay hook.
 *
 * Two shapes of behaviour live here, and the difference matters:
 *
 *  * A **packet transform** rewrites or drops the bytes the game receives.
 *    These are direction-scoped: every packet they touch is clientbound
 *    (BedrockPacketIds: SetTime 0x0A, MobEffect 0x1C, LevelEvent 0x19,
 *    SpawnParticleEffect 0x76, PlayerFog 0xA0 all have toClient=true,
 *    toServer=false), so a serverbound leg is never rewritten — that would be
 *    a packet no server accepts.
 *  * A **derived view** is a pure function over [ModuleContext.entities] (and
 *    over the packet in hand) that returns the plain data an overlay needs:
 *    which entities to box, which lines to stroke, which chunk borders to
 *    draw. It rewrites nothing, because an ESP overlay is drawn *over* the
 *    game rather than being a field of any packet. That was the old reason
 *    for calling these ids impossible ("overlay rendering") and it was half
 *    right: the *drawing* is client-side, but *choosing what to draw* is a
 *    pure function over the live entity table, and that part is real work.
 *
 * Ids whose only remaining half is the render pass itself — xray, view_model,
 * zoom, motion_blur, shader_loader, gui_scale — stay in [IMPOSSIBLE] with the
 * reason narrowed to exactly that.
 *
 * Every transform is pure: one packet in, packets out, no clock, no I/O, no
 * Android. Empty list drops the packet.
 */
object VisualModules {

    /** Ids and layouts, all from registry/bedrock-packets.json. */
    private const val ID_SET_TIME = 0x0A
    private const val ID_MOB_EFFECT = 0x1C
    private const val ID_LEVEL_EVENT = 0x19
    private const val ID_SPAWN_PARTICLE = 0x76
    private const val ID_PLAYER_FOG = 0xA0
    private const val ID_LEVEL_CHUNK = 0x3A
    private const val ID_UPDATE_BLOCK = 0x15
    private const val ID_SET_SPAWN_POSITION = 0x2B

    /** Bedrock day cycle: 0..24000 ticks, 6000 = noon, 18000 = midnight. */
    private const val TIME_NOON = 6000
    private const val TIME_MIDNIGHT = 18000

    /**
     * MobEffect ids. `effect_id` is a plain zigzag32 in the vendored proto with
     * no enum attached, so these two values are protocol constants asserted
     * here rather than derived from a table in this repo — they are why
     * no_blindness/no_nausea can select one effect instead of stripping every
     * status effect the player has.
     */
    const val EFFECT_NAUSEA = 9
    const val EFFECT_BLINDNESS = 15

    /** LevelEvent ids for weather (vendored proto, `packet_level_event`). */
    const val LEVEL_EVENT_START_RAIN = 3001
    const val LEVEL_EVENT_START_THUNDER = 3002
    const val LEVEL_EVENT_STOP_RAIN = 3003
    const val LEVEL_EVENT_STOP_THUNDER = 3004

    /** A chunk is 16 blocks on a side; LevelChunk carries chunk coordinates. */
    private const val CHUNK_BLOCKS = 16

    /**
     * Entity `type` values that are something dropped or thrown on the ground.
     *
     * AddItemEntity 0x0F is the packet a vanilla server uses for these, but its
     * `item` field is an `ItemV4` — a type with no layout in either the
     * vendored proto or the registry — sitting between the runtime id and the
     * position, so the position cannot be reached without inventing a size. The
     * item spawn is therefore read here through the entity table instead, which
     * sees whatever id the server spawns them under.
     */
    private val ITEM_TYPES = setOf(
        "minecraft:item",
        "minecraft:xp_orb",
        "minecraft:xp_bottle",
        "minecraft:arrow",
        "minecraft:snowball",
        "minecraft:ender_pearl",
        "minecraft:egg",
        "minecraft:turtle_egg",
    )

    /**
     * Particles `particle_controls` drops when the setting is unset: combat
     * feedback, not world feedback. Overridable via the
     * `particle_controls.block` setting (comma-separated names).
     */
    private val DEFAULT_BLOCKED_PARTICLES = listOf(
        "minecraft:villager_angry",
        "minecraft:villager_hurt",
        "minecraft:damage_indicator",
        "minecraft:explosion_emitter",
    )

    /** Ids with real behaviour: a packet transform, or a derived view. */
    val IMPLEMENTED: Set<String> = setOf(
        // packet transforms
        "xykell.visual.time_changer",
        "xykell.visual.fullbright",
        "xykell.visual.no_blindness",
        "xykell.visual.no_nausea",
        "xykell.visual.no_weather",
        "xykell.visual.weather_changer",
        "xykell.visual.particle_controls",
        "xykell.visual.fog_controls",
        // derived views over the live entity table
        "xykell.visual.esp",
        "xykell.visual.player_esp",
        "xykell.visual.entity_esp",
        "xykell.visual.item_esp",
        "xykell.visual.nametag",
        "xykell.visual.tracers",
        "xykell.visual.block_tracers",
        "xykell.visual.waypoints",
        "xykell.visual.chunk_borders",
        "xykell.visual.new_chunks",
    )

    /** Ids a relay genuinely cannot deliver, with the reason. */
    val IMPOSSIBLE: Map<String, String> = mapOf(
        "xykell.visual.xray" to
            "block visibility is decided by the client chunk renderer, not by any packet; a " +
            "derived view can see that a block changed (UpdateBlock 0x15) but cannot change " +
            "whether the renderer draws it, so the render half is unreachable from here",
        "xykell.visual.free_look" to
            "decoupling the camera from the body needs the server's copy of the body yaw, which " +
            "lives only in the previous packets; MovePlayer 0x13 already carries pitch/yaw and " +
            "head_yaw separately, so a stateless rewrite can only turn the body with the camera",
        "xykell.visual.free_cam" to
            "needs a detached camera fed by local input; the relay forwards gameplay packets " +
            "and has no input or camera state",
        "xykell.visual.no_invisible" to
            "invisibility is an entity metadata flag, and metadata is an opaque type in both " +
            "the vendored proto and the registry: AddPlayer 0x0C/AddEntity 0x0D name " +
            "MetadataDictionary with no field layout, so the flag has no offset to filter on; " +
            "dropping the whole spawn packet would remove the entity rather than its alpha",
        "xykell.visual.no_fire" to
            "the burning overlay is driven by the ONFIRE entity metadata flag, which is " +
            "undecodable for the same reason as no_invisible, and neither LevelEvent 0x19 nor " +
            "EntityEvent 0x1B carries a fire event in the vendored enum, so no packet this repo " +
            "can read says the player is on fire",
        "xykell.visual.hole_esp" to
            "a hole is an air pocket in the decoded sub-chunk block array; chunk data reaches " +
            "this repo only as the opaque LevelChunk 0x3A payload, so there is nothing to scan " +
            "and only the client's renderer could draw the result",
        "xykell.visual.spawner_esp" to
            "a mob spawner is identified by the block at a position, and UpdateBlock 0x15 " +
            "carries only a numeric block_runtime_id with no runtime-id-to-name palette anywhere " +
            "in this repo, so the relay cannot tell a spawner from any other block",
        "xykell.visual.spawner_ping" to
            "world interaction: pinging a spawner is a client action with no packet a relay " +
            "could originate, and its position is unknowable for the reason spawner_esp gives",
        "xykell.visual.sus_chunk_finder" to
            "flagging a chunk means knowing when it was generated, and no clientbound packet " +
            "carries a generation time or the generator that produced a specific chunk; " +
            "StartGame 0x0B names the generator once for the whole world and this repo decodes " +
            "none of its payload",
        "xykell.visual.bed_esp" to
            "a bed is a block identified by name, so it hits the same wall as spawner_esp: " +
            "0x15 gives a runtime id and no palette maps it to a bed",
        "xykell.visual.minimap" to
            "a terrain minimap needs a rendered surface plus block sampling per column, and " +
            "block sampling needs the decoded sub-chunk array the relay does not hold; entity " +
            "positions alone cannot draw terrain",
        "xykell.visual.schematic" to
            "world fill: requires injecting held-item/block changes as client-bound input the " +
            "relay cannot author",
        "xykell.visual.view_model" to
            "first-person arm/hand rendering and item transform are client renderer state",
        "xykell.visual.zoom" to
            "camera FOV is applied by the client camera, which the relay cannot read or change",
        "xykell.visual.motion_blur" to
            "post-process render effect with no packet representation",
        "xykell.visual.shader_loader" to
            "resource-pack/shader install is a client resource pipeline task, not a relayed packet",
        "xykell.visual.custom_crosshair" to
            "HUD overlay drawn by the client crosshair renderer",
        "xykell.visual.hit_effects" to
            "render effect plus local input timing; neither exists in the packet stream",
        "xykell.visual.camera_controls" to
            "camera state is client-side and no packet carries it; only the rotation-only rewrite " +
            "freeLook would need is claimable, and that is itself unreachable above",
        "xykell.visual.block_outline" to
            "a selection outline is the renderer highlighting whatever block the client's own " +
            "look-at raycast found; the relay can decode UpdateBlock 0x15 (which is what " +
            "block_tracers draws) but it never learns which block the player is aiming at",
        "xykell.visual.item_physics" to
            "SetEntityMotion 0x28 names a runtime id and a velocity but never the entity's type, " +
            "so item motion can only be singled out by remembering which ids an earlier " +
            "AddItemEntity 0x0F spawned — state across calls, and 0x0F's position is unreachable " +
            "past its undecodable ItemV4",
        "xykell.visual.gui_scale" to
            "GUI scale is a client UI setting with no packet representation",
    )

    // ============================================================ packet transforms

    fun transform(
        id: String,
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext = ModuleContext(),
    ): List<ByteArray> = when (id) {
        "xykell.visual.time_changer" -> forceTime(direction, packet, TIME_MIDNIGHT)
        "xykell.visual.fullbright" -> forceTime(direction, packet, TIME_NOON)
        "xykell.visual.no_blindness" -> dropMobEffect(direction, packet, EFFECT_BLINDNESS)
        "xykell.visual.no_nausea" -> dropMobEffect(direction, packet, EFFECT_NAUSEA)
        "xykell.visual.no_weather" -> stopWeather(direction, packet)
        "xykell.visual.weather_changer" -> clearWeather(direction, packet)
        "xykell.visual.particle_controls" -> filterParticles(direction, packet, ctx)
        "xykell.visual.fog_controls" -> fogStack(direction, packet, ctx)
        // derived-view ids: no bytes to rewrite, so they forward untouched.
        else -> listOf(packet) // not in IMPLEMENTED: forward untouched
    }

    /**
     * Replace the SetTime day-cycle value with [ticks]. Any packet that is not
     * exactly a header plus one zigzag varint is forwarded untouched — including
     * a SetTime whose body this repo's decode would not consume, because
     * rewriting bytes we do not understand is how a relay corrupts a session.
     */
    private fun forceTime(direction: RelayDirection, packet: ByteArray, ticks: Int): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        if (ModuleWire.id(packet) != ID_SET_TIME) return listOf(packet)
        val body = ModuleWire.bodyStart(packet) ?: return listOf(packet)
        val (current, end) = ModuleWire.readVarInt(packet, body) ?: return listOf(packet)
        if (end != packet.size) return listOf(packet)
        if (current == ticks) return listOf(packet)
        return listOf(ModuleWire.splice(packet, body, end, ModuleWire.writeVarInt(ticks)))
    }

    /**
     * Drop the MobEffect 0x1C packets that apply [effect]. One effect id is
     * selected, so every other status effect still reaches the game — the
     * whole-packet drop that the old IMPOSSIBLE note warned about is avoided
     * precisely because `effect_id` is a decodable zigzag32 at a known offset.
     */
    private fun dropMobEffect(
        direction: RelayDirection,
        packet: ByteArray,
        effect: Int,
    ): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        val fields = mobEffect(packet) ?: return listOf(packet)
        return if (fields.effectId == effect) emptyList() else listOf(packet)
    }

    /**
     * Stop rain and thunder by dropping the LevelEvent 0x19 ids that start
     * them. Stop ids are not injected: the relay has no business inventing an
     * event the server did not send, and dropping the start is enough for a
     * client that was not already raining.
     */
    private fun stopWeather(direction: RelayDirection, packet: ByteArray): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        val fields = levelEvent(packet) ?: return listOf(packet)
        val starting = fields.event == LEVEL_EVENT_START_RAIN ||
            fields.event == LEVEL_EVENT_START_THUNDER
        return if (starting) emptyList() else listOf(packet)
    }

    /**
     * Rewrite the two LevelEvent 0x19 weather ids into their stop counterparts
     * (start_rain -> stop_rain, start_thunder -> stop_thunder). Unlike
     * stopWeather this keeps the packet, so the client's weather state machine
     * runs the same transition it would on a real stop instead of never
     * learning the weather began.
     */
    private fun clearWeather(direction: RelayDirection, packet: ByteArray): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        val fields = levelEvent(packet) ?: return listOf(packet)
        val replacement = when (fields.event) {
            LEVEL_EVENT_START_RAIN -> LEVEL_EVENT_STOP_RAIN
            LEVEL_EVENT_START_THUNDER -> LEVEL_EVENT_STOP_THUNDER
            else -> return listOf(packet)
        }
        return listOf(
            ModuleWire.splice(
                packet,
                fields.eventStart,
                fields.eventEnd,
                ModuleWire.writeVarInt(replacement),
            ),
        )
    }

    /**
     * Drop SpawnParticleEffect 0x76 packets whose `particle_name` is blocked.
     * The name is the last field of the packet, so the rewrite needs no
     * re-encoding: dropping the packet is the whole mechanism, and the client
     * never spawns the particle.
     */
    private fun filterParticles(
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext,
    ): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        val name = particleName(packet) ?: return listOf(packet)
        return if (blockedParticles(ctx).contains(name)) emptyList() else listOf(packet)
    }

    private fun blockedParticles(ctx: ModuleContext): List<String> {
        val override = ctx.settings["particle_controls.block"] ?: return DEFAULT_BLOCKED_PARTICLES
        return override.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Rewrite the PlayerFog 0xA0 stack. Fog is a real client-rendered term, but
     * the stack that produces it is a clientbound packet field, so this is a
     * genuine wire rewrite rather than a render pass: an empty stack means no
     * fog effect applies. `fog_controls.stack` overrides it with a
     * comma-separated stack.
     */
    private fun fogStack(
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext,
    ): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        val header = ModuleWire.header(packet) ?: return listOf(packet)
        if (header and ModuleWire.ID_MASK != ID_PLAYER_FOG) return listOf(packet)
        val current = readFogStack(packet) ?: return listOf(packet)
        val wanted = ctx.settings["fog_controls.stack"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        if (current == wanted) return listOf(packet)
        return listOf(
            ModuleWire.build(
                header,
                ModuleWire.writeVarUInt(wanted.size),
                *wanted.map { ModuleWire.writeVarString(it) }.toTypedArray(),
            ),
        )
    }

    // ================================================================ derived views

    /**
     * One drawable ESP target. [name] is the AddPlayer username, or the
     * AddEntity type string for anything that is not a player.
     */
    data class Target(
        val runtimeId: Long,
        val name: String,
        val x: Float,
        val y: Float,
        val z: Float,
        val distance: Float,
        val isPlayer: Boolean,
    )

    /** The two endpoints of a tracer overlay stroke. */
    data class Line(
        val fromX: Float,
        val fromY: Float,
        val fromZ: Float,
        val toX: Float,
        val toY: Float,
        val toZ: Float,
        val length: Float,
    )

    /**
     * A chunk the relay has just seen, in both chunk and block coordinates.
     * [subChunks] is the LevelChunk 0x3A count, i.e. how much terrain arrived.
     */
    data class ChunkMark(
        val chunkX: Int,
        val chunkZ: Int,
        val worldX: Int,
        val worldZ: Int,
        val subChunks: Int,
        val distance: Float,
    )

    /** A single block change from UpdateBlock 0x15. */
    data class BlockMark(
        val x: Int,
        val y: Int,
        val z: Int,
        val blockRuntimeId: Int,
        val distance: Float,
    )

    /** A named anchor an overlay or navigation list can show. */
    data class Waypoint(
        val name: String,
        val x: Float,
        val y: Float,
        val z: Float,
        val distance: Float,
    )

    /**
     * The ESP target set: every entity the entity table knows about, excluding
     * the local player, within [maxDistance] of the local player's reported
     * position, nearest first.
     *
     * This is the whole of `esp`: the filtering, the distances and the ordering
     * an overlay needs. It reads no bytes and mutates nothing.
     */
    fun targets(ctx: ModuleContext, maxDistance: Float, playersOnly: Boolean): List<Target> =
        ctx.entities.all()
            .filter { it.runtimeId != ctx.selfRuntimeId }
            .filter { !playersOnly || it.isPlayer }
            .mapNotNull { toTarget(it, ctx, maxDistance) }
            .sortedBy { it.distance }

    /** `player_esp`: players only. */
    fun playerTargets(ctx: ModuleContext, maxDistance: Float): List<Target> =
        targets(ctx, maxDistance, playersOnly = true)

    /**
     * `entity_esp`: everything that is not a player, optionally narrowed to the
     * entity types containing [typeFilter] (case-insensitive, empty matches all).
     */
    fun entityTargets(
        ctx: ModuleContext,
        maxDistance: Float,
        typeFilter: String,
    ): List<Target> =
        targets(ctx, maxDistance, playersOnly = false)
            .filter { !it.isPlayer }
            .filter { typeFilter.isEmpty() || it.name.contains(typeFilter, ignoreCase = true) }

    /** `item_esp`: dropped items and throwables on the ground. */
    fun itemTargets(ctx: ModuleContext, maxDistance: Float): List<Target> =
        targets(ctx, maxDistance, playersOnly = false)
            .filter { it.name in ITEM_TYPES }

    /**
     * `nametag`: only the entities that actually carry a name, so an overlay
     * does not render an empty tag over every mob.
     */
    fun namedTargets(ctx: ModuleContext, maxDistance: Float): List<Target> =
        targets(ctx, maxDistance, playersOnly = false)
            .filter { it.isPlayer && !it.name.isBlank() }

    /** `tracers`: the ESP target set as world-space lines out of the player. */
    fun tracers(ctx: ModuleContext, maxDistance: Float): List<Line> =
        targets(ctx, maxDistance, playersOnly = false).map { lineFromSelf(ctx, it) }

    /**
     * `block_tracers` / `world.block_esp`: the block changed by an UpdateBlock
     * 0x15, as a marker and as a line out of the player.
     */
    fun blockMarkers(ctx: ModuleContext, packet: ByteArray, maxDistance: Float): List<BlockMark> {
        val change = updateBlock(packet) ?: return emptyList()
        val distance = distance(ctx, change.x + 0.5f, change.y + 0.5f, change.z + 0.5f)
        if (distance > maxDistance) return emptyList()
        return listOf(
            BlockMark(change.x, change.y, change.z, change.blockRuntimeId, distance),
        )
    }

    fun blockTracers(ctx: ModuleContext, packet: ByteArray, maxDistance: Float): List<Line> =
        blockMarkers(ctx, packet, maxDistance).map {
            Line(
                ctx.selfX, ctx.selfY, ctx.selfZ,
                it.x + 0.5f, it.y + 0.5f, it.z + 0.5f,
                it.distance,
            )
        }

    /**
     * `world.chunk_finder`: where the chunk in this packet
     * sits, in chunk and block coordinates, with its distance from the player.
     * One LevelChunk arrives per packet, so this describes that chunk.
     */
    fun chunkMarks(ctx: ModuleContext, packet: ByteArray, maxDistance: Float): List<ChunkMark> {
        val chunk = levelChunk(packet) ?: return emptyList()
        val mark = toChunkMark(ctx, chunk)
        return if (mark.distance <= maxDistance) listOf(mark) else emptyList()
    }

    /**
     * `new_chunks` / `world.new_chunks`: the chunks that arrived from outside
     * the client's current chunk radius — the ones a new-chunk highlight is
     * for. The radius comes from the `chunk_radius` setting and falls back to
     * [DEFAULT_CHUNK_RADIUS]; it is not read from the ChunkRadiusUpdate 0x46
     * packet, because holding that value would be state across calls.
     */
    fun newChunkMarks(ctx: ModuleContext, packet: ByteArray): List<ChunkMark> {
        val chunk = levelChunk(packet) ?: return emptyList()
        val radius = ctx.int("chunk_radius", DEFAULT_CHUNK_RADIUS)
        val mark = toChunkMark(ctx, chunk)
        val selfChunkX = Math.floorDiv(ctx.selfX.toInt(), CHUNK_BLOCKS)
        val selfChunkZ = Math.floorDiv(ctx.selfZ.toInt(), CHUNK_BLOCKS)
        val outside = Math.max(
            Math.abs(mark.chunkX - selfChunkX),
            Math.abs(mark.chunkZ - selfChunkZ),
        ) > radius
        return if (outside) listOf(mark) else emptyList()
    }

    /**
     * `chunk_borders` / `world.chunk_borders`: the four world-space edges of
     * the chunk in this packet, which is the geometry a chunk-border overlay
     * strokes.
     */
    fun chunkBorderLines(ctx: ModuleContext, packet: ByteArray): List<Line> {
        val chunk = levelChunk(packet) ?: return emptyList()
        val x0 = chunk.x * CHUNK_BLOCKS
        val z0 = chunk.z * CHUNK_BLOCKS
        val x1 = x0 + CHUNK_BLOCKS
        val z1 = z0 + CHUNK_BLOCKS
        val y = ctx.selfY
        return listOf(
            Line(x0.toFloat(), y, z0.toFloat(), x1.toFloat(), y, z0.toFloat(), 0f),
            Line(x0.toFloat(), y, z1.toFloat(), x1.toFloat(), y, z1.toFloat(), 0f),
            Line(x0.toFloat(), y, z0.toFloat(), x0.toFloat(), y, z1.toFloat(), 0f),
            Line(x1.toFloat(), y, z0.toFloat(), x1.toFloat(), y, z1.toFloat(), 0f),
        )
    }

    /**
     * `waypoints`: the spawn anchors SetSpawnPosition 0x2B carries, as named
     * points with their distance from the player.
     */
    fun spawnWaypoints(ctx: ModuleContext, packet: ByteArray): List<Waypoint> {
        val spawn = setSpawnPosition(packet) ?: return emptyList()
        return listOf(
            Waypoint("spawn", spawn.playerX, spawn.playerY, spawn.playerZ,
                distance(ctx, spawn.playerX, spawn.playerY, spawn.playerZ)),
            Waypoint("world_spawn", spawn.worldX, spawn.worldY, spawn.worldZ,
                distance(ctx, spawn.worldX, spawn.worldY, spawn.worldZ)),
        )
    }

    /** Default view radius, in chunks, when no ChunkRadiusUpdate was seen. */
    const val DEFAULT_CHUNK_RADIUS = 8

    private fun toTarget(
        entity: EntityTable.Entity,
        ctx: ModuleContext,
        maxDistance: Float,
    ): Target? {
        val d = distance(ctx, entity.x, entity.y, entity.z)
        if (d > maxDistance) return null
        return Target(
            runtimeId = entity.runtimeId,
            name = entity.name ?: entity.type,
            x = entity.x,
            y = entity.y,
            z = entity.z,
            distance = d,
            isPlayer = entity.isPlayer,
        )
    }

    private fun lineFromSelf(ctx: ModuleContext, target: Target): Line = Line(
        fromX = ctx.selfX,
        fromY = ctx.selfY,
        fromZ = ctx.selfZ,
        toX = target.x,
        toY = target.y,
        toZ = target.z,
        length = target.distance,
    )

    private fun toChunkMark(ctx: ModuleContext, chunk: ChunkFields): ChunkMark {
        val worldX = chunk.x * CHUNK_BLOCKS
        val worldZ = chunk.z * CHUNK_BLOCKS
        return ChunkMark(
            chunkX = chunk.x,
            chunkZ = chunk.z,
            worldX = worldX,
            worldZ = worldZ,
            subChunks = chunk.subChunks,
            distance = distance(
                ctx,
                worldX + CHUNK_BLOCKS / 2f,
                ctx.selfY,
                worldZ + CHUNK_BLOCKS / 2f,
            ),
        )
    }

    private fun distance(ctx: ModuleContext, x: Float, y: Float, z: Float): Float {
        val dx = x - ctx.selfX
        val dy = y - ctx.selfY
        val dz = z - ctx.selfZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ==================================================================== decoding

    /** One decoded block change: UpdateBlock 0x15. */
    private data class BlockFields(
        val x: Int,
        val y: Int,
        val z: Int,
        val blockRuntimeId: Int,
    )

    /** One decoded chunk: the fixed prefix of LevelChunk 0x3A. */
    private data class ChunkFields(val x: Int, val z: Int, val subChunks: Int)

    /** One decoded MobEffect 0x1C. */
    private data class EffectFields(val effectId: Int)

    /**
     * One decoded LevelEvent 0x19. The event field's byte range is kept so a
     * rewrite can replace exactly it and leave position and data untouched.
     */
    private data class EventFields(
        val event: Int,
        val eventStart: Int,
        val eventEnd: Int,
        val data: Int,
    )

    private data class SpawnFields(
        val playerX: Float,
        val playerY: Float,
        val playerZ: Float,
        val worldX: Float,
        val worldY: Float,
        val worldZ: Float,
    )

    /**
     * UpdateBlock 0x15: three zigzag32 block coordinates, a varint
     * block_runtime_id, a varint flag set and a varint layer. Every field is
     * required to be readable and the packet to end there; anything else is
     * forwarded rather than half-parsed.
     */
    private fun updateBlock(packet: ByteArray): BlockFields? {
        if (ModuleWire.id(packet) != ID_UPDATE_BLOCK) return null
        val body = ModuleWire.bodyStart(packet) ?: return null
        val x = ModuleWire.readVarInt(packet, body) ?: return null
        val y = ModuleWire.readVarInt(packet, x.second) ?: return null
        val z = ModuleWire.readVarInt(packet, y.second) ?: return null
        val id = ModuleWire.readVarUInt(packet, z.second) ?: return null
        val flags = ModuleWire.readVarUInt(packet, id.second) ?: return null
        val layer = ModuleWire.readVarUInt(packet, flags.second) ?: return null
        if (layer.second != packet.size) return null
        return BlockFields(x.first, y.first, z.first, id.first)
    }

    /**
     * The fixed prefix of LevelChunk 0x3A: chunk x, chunk z, dimension and the
     * sub-chunk count. Parsing stops there — the optional highest_subchunk_count
     * that follows makes the rest of the packet layout-dependent, so nothing
     * after it is read.
     */
    private fun levelChunk(packet: ByteArray): ChunkFields? {
        if (ModuleWire.id(packet) != ID_LEVEL_CHUNK) return null
        val body = ModuleWire.bodyStart(packet) ?: return null
        val x = ModuleWire.readVarInt(packet, body) ?: return null
        val z = ModuleWire.readVarInt(packet, x.second) ?: return null
        val dimension = ModuleWire.readVarInt(packet, z.second) ?: return null
        val count = ModuleWire.readVarUInt(packet, dimension.second) ?: return null
        return ChunkFields(x.first, z.first, count.first)
    }

    /**
     * MobEffect 0x1C: a varulong runtime id, a u8 event, then the zigzag32
     * effect id. The runtime id's width is measured rather than decoded, so a
     * wide id costs nothing and a truncated one fails closed.
     */
    private fun mobEffect(packet: ByteArray): EffectFields? {
        if (ModuleWire.id(packet) != ID_MOB_EFFECT) return null
        val body = ModuleWire.bodyStart(packet) ?: return null
        val idWidth = ModuleWire.varUIntSize(packet, body) ?: return null
        val event = ModuleWire.readByte(packet, body + idWidth) ?: return null
        val effect = ModuleWire.readVarInt(packet, event.second) ?: return null
        // amplifier, particles, duration, tick, ambient must all be present for
        // the id read to be trustworthy.
        val amplifier = ModuleWire.readVarInt(packet, effect.second) ?: return null
        val particles = ModuleWire.readBool(packet, amplifier.second) ?: return null
        val duration = ModuleWire.readVarInt(packet, particles.second) ?: return null
        val tickWidth = ModuleWire.varUIntSize(packet, duration.second) ?: return null
        val at = duration.second + tickWidth
        val ambient = ModuleWire.readBool(packet, at) ?: return null
        if (ambient.second != packet.size) return null
        return EffectFields(effect.first)
    }

    /** LevelEvent 0x19: a zigzag32 event, a vec3f position and a zigzag32 data. */
    private fun levelEvent(packet: ByteArray): EventFields? {
        if (ModuleWire.id(packet) != ID_LEVEL_EVENT) return null
        val body = ModuleWire.bodyStart(packet) ?: return null
        val event = ModuleWire.readVarInt(packet, body) ?: return null
        var at = event.second
        repeat(3) {
            val next = ModuleWire.readF32LE(packet, at)?.second ?: return null
            at = next
        }
        val data = ModuleWire.readVarInt(packet, at) ?: return null
        if (data.second != packet.size) return null
        return EventFields(event.first, body, event.second, data.first)
    }

    /** SpawnParticleEffect 0x76: u8 dimension, zigzag64 entity id, vec3f, name. */
    private fun particleName(packet: ByteArray): String? {
        if (ModuleWire.id(packet) != ID_SPAWN_PARTICLE) return null
        val body = ModuleWire.bodyStart(packet) ?: return null
        val dimension = ModuleWire.readByte(packet, body) ?: return null
        val idWidth = ModuleWire.varUIntSize(packet, dimension.second) ?: return null
        var at = dimension.second + idWidth
        repeat(3) {
            val next = ModuleWire.readF32LE(packet, at)?.second ?: return null
            at = next
        }
        val name = ModuleWire.readVarString(packet, at) ?: return null
        if (name.second != packet.size) return null
        return name.first
    }

    /** PlayerFog 0xA0: a varint count then that many length-prefixed names. */
    private fun readFogStack(packet: ByteArray): List<String>? {
        if (ModuleWire.header(packet)?.and(ModuleWire.ID_MASK) != ID_PLAYER_FOG) return null
        val body = ModuleWire.bodyStart(packet) ?: return null
        val (count, after) = ModuleWire.readVarUInt(packet, body) ?: return null
        if (count < 0) return null
        var at = after
        val stack = ArrayList<String>(count)
        repeat(count) {
            val entry = ModuleWire.readVarString(packet, at) ?: return null
            stack.add(entry.first)
            at = entry.second
        }
        if (at != packet.size) return null
        return stack
    }

    /** SetSpawnPosition 0x2B: two BlockCoordinates plus a dimension. */
    private fun setSpawnPosition(packet: ByteArray): SpawnFields? {
        if (ModuleWire.id(packet) != ID_SET_SPAWN_POSITION) return null
        val body = ModuleWire.bodyStart(packet) ?: return null
        val type = ModuleWire.readVarInt(packet, body) ?: return null
        val px = ModuleWire.readVarInt(packet, type.second) ?: return null
        val py = ModuleWire.readVarInt(packet, px.second) ?: return null
        val pz = ModuleWire.readVarInt(packet, py.second) ?: return null
        val dimension = ModuleWire.readVarInt(packet, pz.second) ?: return null
        val wx = ModuleWire.readVarInt(packet, dimension.second) ?: return null
        val wy = ModuleWire.readVarInt(packet, wx.second) ?: return null
        val wz = ModuleWire.readVarInt(packet, wy.second) ?: return null
        if (wz.second != packet.size) return null
        return SpawnFields(
            px.first.toFloat(), py.first.toFloat(), pz.first.toFloat(),
            wx.first.toFloat(), wy.first.toFloat(), wz.first.toFloat(),
        )
    }
}