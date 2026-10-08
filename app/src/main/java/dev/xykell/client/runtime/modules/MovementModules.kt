package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.BedrockPacketIds
import dev.xykell.client.runtime.relay.RelayDirection
/**
 * MOVEMENT registry batch: pure relay-packet transforms for `xykell.movement.*`.
 *
 * Every transform is pure except for [ctx], the per-session state the session
 * owns: one packet in, packets out, no sockets, no Android, no clock. An empty
 * list drops the packet.
 *
 * The transforms are direction-scoped, which is what makes them correct rather
 * than merely plausible: a module that lies about where the player is rewrites
 * the player's own outbound report ([RelayDirection.TO_SERVER]) and leaves the
 * server's view of every other entity ([RelayDirection.TO_CLIENT]) alone.
 * MovePlayer 0x13 is `bound: both`, so the leg check is the only thing keeping
 * "my speed" from teleporting everyone else in the world.
 *
 * Two things make the previously-impossible ids reachable. First, [ctx] carries
 * the previous self position, the last on-ground height and the last on-ground
 * flag, so a transform can measure a delta instead of reading one absolute
 * position in isolation. Second, the ids and field layouts come from the
 * generated [BedrockPacketIds] table and `registry/bedrock-packets.json`
 * rather than from memory.
 *
 * What is still out of reach is stated rather than faked. The whole
 * sprint/sneak/jump/glide family lives in PlayerAuthInput 0x90's
 * `input_data?: InputData[]varint`, an *optional list* as of 1.26.40 whose
 * element framing and presence byte upstream does not define — so the relay
 * can neither read the current flags nor write new ones without guessing a
 * layout and corrupting every field after it. Those ids stay in [IMPOSSIBLE]
 * with that specific reason. So do the ids that need block data or inventory
 * state, which never leave the client.
 */
object MovementModules {

    /** Bedrock MovePlayer — the only movement packet this repo decodes. */
    val MOVE_PLAYER: Int = BedrockPacketIds.infoOf("MovePlayer")!!.id

    /** MovePlayer mode for an ordinary position update (not reset/teleport). */
    const val MODE_NORMAL = 0

    /** Blocks added to every MovePlayer y by `xykell.movement.levitate`. */
    const val LEVITATE_LIFT = 1.5f

    /** Horizontal delta multiplier for `speed` when the session sets none. */
    const val DEFAULT_SPEED_MULTIPLIER = 1.2f

    /** Blocks per tick `slow_falling` allows as a downward step. */
    const val SLOW_FALLING_MAX_DESCENT = 0.2f

    /** Blocks `fly` adds to every reported y. */
    const val FLY_LIFT = 0.5f

    /** Horizontal delta multiplier for `motion_fly`. */
    const val MOTION_FLY_MOMENTUM = 1.35f

    /** Blocks `motion_fly` adds to every reported y. */
    const val MOTION_FLY_LIFT = 0.35f

    /** Blocks `jump_boost` adds on every tick the client reports rising. */
    const val JUMP_BOOST_RISE = 0.3f

    const val SETTING_SPEED_MULTIPLIER = "speed_multiplier"
    const val SETTING_SLOW_FALLING_MAX_DESCENT = "slow_falling_max_descent"
    const val SETTING_FLY_LIFT = "fly_lift"
    const val SETTING_MOTION_FLY_MOMENTUM = "motion_fly_momentum"
    const val SETTING_MOTION_FLY_LIFT = "motion_fly_lift"
    const val SETTING_JUMP_BOOST_RISE = "jump_boost_rise"

    private const val X_OFFSET = 0
    private const val Y_OFFSET = 4
    private const val Z_OFFSET = 8
    private const val MODE_OFFSET = 24
    private const val ON_GROUND_OFFSET = 25

    /** Ids with a real transform. */
    val IMPLEMENTED: Set<String> = setOf(
        "xykell.movement.levitate",
        "xykell.movement.movement_correction",
        "xykell.movement.speed",
        "xykell.movement.no_fall",
        "xykell.movement.slow_falling",
        "xykell.movement.fly",
        "xykell.movement.motion_fly",
        "xykell.movement.jump_boost",
    )

    /** Ids a relay genuinely cannot deliver, with the reason. */
    val IMPOSSIBLE: Map<String, String> = mapOf(
        "xykell.movement.sprint" to
            "sprint is the start_sprinting (25) / sprinting (20) entry of PlayerAuthInput 0x90's " +
            "input_data?: InputData[]varint; as of 1.26.40 that is an optional list rather than the " +
            "old bitfield and upstream specifies no presence byte or element framing, so the relay " +
            "cannot locate or write it without corrupting every field behind it.",
        "xykell.movement.auto_sprint" to
            "the forward-held trigger is readable (PlayerAuthInput 0x90 move_vector is a fixed " +
            "vec2f), but the effect is not: sprint is only expressible as an input_data entry, the " +
            "ambiguous optional list of InputData ordinals, and PlayerAction 0x24 has no sprint " +
            "action of its own.",
        "xykell.movement.no_slow" to
            "the server derives the item-use slowdown from the use data carried in the same packet " +
            "and the factor depends on which item is held; no inventory packet is decoded here, so " +
            "the un-slowed distance is not derivable from the bytes.",
        "xykell.movement.step" to
            "step assist must know the block under the player's feet to know the step height; block " +
            "data arrives in LevelChunk/UpdateBlock packets this repo does not decode and the relay " +
            "keeps no world.",
        "xykell.movement.air_jump" to
            "a mid-air jump is an impulse, and no outbound packet in this repo carries one: " +
            "MovePlayer 0x13 is an absolute position, PlayerAction 0x24's Action ordinals are not " +
            "even expanded in the vendored proto, and the jump itself is the start_jumping (31) " +
            "input_data entry the relay cannot write.",
        "xykell.movement.glide" to
            "gliding is held input: descend (1) / ascend (0) in PlayerAuthInput 0x90's input_data " +
            "optional list, whose framing upstream leaves undefined, so the deploy and the sustained " +
            "descent toggle are both unwritable.",
        "xykell.movement.auto_elytra" to
            "needs to know the chest slot holds an elytra and to fire the launch impulse; the slot " +
            "contents live in inventory packets this repo does not decode and the impulse is not a " +
            "field of any packet it does decode.",
        "xykell.movement.auto_rocket" to
            "needs the firework rocket in the chest slot plus the moment to consume it; inventory " +
            "state is never decoded and the firework launch is a client-side impulse, not a field.",
        "xykell.movement.jetpack" to
            "needs the elytra-equipped input flag and a rising impulse held across ticks; the flag " +
            "is an input_data entry of the unwritable optional list and MovePlayer 0x13 has no " +
            "impulse field.",
        "xykell.movement.phase" to
            "walking through blocks is a client collision/physics property; it needs block data the " +
            "relay does not hold, and a position inside a solid block is a position the server " +
            "rubber-bands straight back.",
        "xykell.movement.bunny_hop" to
            "auto-hop is two halves: the ground transition is now visible through ctx.selfOnGround, " +
            "but the jump it must fire on that transition is the start_jumping (31) input_data entry " +
            "in PlayerAuthInput 0x90, and no packet this repo decodes carries a jump impulse.",
        "xykell.movement.water_walk" to
            "requires knowing the player stands in a water block; fluid state lives in chunk block " +
            "data this repo does not decode, so neither the trigger nor the surface height is known.",
        "xykell.movement.tap_tp" to
            "a double-tap needs elapsed time between two taps, and ctx.tick is a logical counter with " +
            "no wall-clock mapping; the destination is equally missing, since no tracked field holds " +
            "a stored teleport target.",
        "xykell.movement.safe_walk" to
            "edge detection needs the block beside the player's feet, which arrives in chunk data " +
            "this repo does not decode; without it every tile edge looks identical to solid floor.",
        "xykell.movement.timer" to
            "a movement timer measures elapsed wall-clock time against a session start; ctx.tick is a " +
            "monotonic logical counter with no clock behind it, and a pure transform may not read " +
            "one.",
        "xykell.movement.ladder_fly" to
            "needs to know the player is on a ladder (block data this repo does not decode) plus a " +
            "sustained ascend (1) input_data entry, so both halves are unreachable.",
        "xykell.movement.block_fly" to
            "block fly must place a block and then climb; the placement needs the hotbar block id " +
            "from inventory state this repo never decodes, and the climb needs the ascend input " +
            "flag plus an impulse no decoded packet carries.",
    )

    fun transform(
        id: String,
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext = ModuleContext(),
    ): List<ByteArray> = when (id) {
        "xykell.movement.levitate" -> levitate(direction, packet)
        "xykell.movement.movement_correction" -> movementCorrection(direction, packet)
        "xykell.movement.speed" -> speed(direction, packet, ctx)
        "xykell.movement.no_fall" -> noFall(direction, packet, ctx)
        "xykell.movement.slow_falling" -> slowFalling(direction, packet, ctx)
        "xykell.movement.fly" -> fly(direction, packet, ctx)
        "xykell.movement.motion_fly" -> motionFly(direction, packet, ctx)
        "xykell.movement.jump_boost" -> jumpBoost(direction, packet, ctx)
        else -> listOf(packet) // not in IMPLEMENTED: forward untouched
    }

    /**
     * One parsed MovePlayer: where the three position floats sit and what they
     * hold. A private holder rather than eight nullable locals because five
     * transforms need the same three reads.
     */
    internal class Move(
        val rotation: Int,
        val runtimeId: Long,
        val x: Float,
        val y: Float,
        val z: Float,
        val onGround: Boolean,
    )

    /**
     * Body offset of the rotation block of a MovePlayer (x, y, z, pitch, yaw,
     * headYaw, mode, onGround), or null when `raw` is not a MovePlayer whose
     * first position float is readable. The runtime id ahead of it is a
     * varulong, so its width has to be measured, not assumed.
     *
     * Shared: `xykell.player.no_fall` rewrites the same y byte, and a second
     * copy of this offset arithmetic is exactly the drift the generated
     * BedrockPacketIds table exists to stop.
     */
    internal fun rotationOffset(raw: ByteArray): Int? {
        if (ModuleWire.id(raw) != MOVE_PLAYER) return null
        val body = ModuleWire.bodyStart(raw) ?: return null
        val runtimeIdWidth = ModuleWire.varUIntSize(raw, body) ?: return null
        val rotation = body + runtimeIdWidth
        if (ModuleWire.readF32LE(raw, rotation) == null) return null
        return rotation
    }

    /**
     * Full parse of an outbound MovePlayer, or null when the packet is another
     * id, is truncated before on_ground, or carries a runtime id too wide for
     * [ModuleWire.readVarUInt]. Requiring on_ground is what lets every caller
     * treat null as "forward this untouched rather than guess".
     */
    internal fun parseMove(raw: ByteArray): Move? {
        if (ModuleWire.id(raw) != MOVE_PLAYER) return null
        val body = ModuleWire.bodyStart(raw) ?: return null
        val (runtimeId, rotation) = ModuleWire.readVarUInt(raw, body) ?: return null
        val (x, afterX) = ModuleWire.readF32LE(raw, rotation) ?: return null
        val (y, afterY) = ModuleWire.readF32LE(raw, afterX) ?: return null
        val (z, _) = ModuleWire.readF32LE(raw, afterY) ?: return null
        val (onGround, _) = ModuleWire.readBool(raw, rotation + ON_GROUND_OFFSET) ?: return null
        return Move(rotation, runtimeId.toLong(), x, y, z, onGround)
    }

    /**
     * Record what the client *itself* reported. The rewritten bytes are never
     * fed back: a module that measured its own lie would compound it every tick.
     *
     * Callers must read the previous position out of [ctx] *before* calling
     * this, because it overwrites selfX/selfY/selfZ with the current packet.
     */
    private fun observe(move: Move, ctx: ModuleContext) =
        ctx.updateSelf(move.runtimeId, move.x, move.y, move.z, move.onGround)

    /** True once [ctx] holds a position from a previous packet in this session. */
    private fun ModuleContext.isPrimed(): Boolean = selfRuntimeId != Long.MIN_VALUE

    /**
     * The player's own outbound MovePlayer leaves with y raised by
     * [LEVITATE_LIFT], so the server places the player higher than the client
     * claims. Scoped to [RelayDirection.TO_SERVER]; the server's own view of
     * the player is the other direction and is left authoritative. The offset
     * does not accumulate (each packet carries an absolute y), so no call
     * history is needed.
     */
    private fun levitate(direction: RelayDirection, packet: ByteArray): List<ByteArray> {
        val rotation = rotationOffset(packet) ?: return listOf(packet)
        val (y, _) = ModuleWire.readF32LE(packet, rotation + Y_OFFSET) ?: return listOf(packet)
        return listOf(
            ModuleWire.put(packet, rotation + Y_OFFSET, ModuleWire.writeF32LE(y + LEVITATE_LIFT)),
        )
    }

    /**
     * Drops MovePlayer packets whose mode is a position reset (mode != 0), which
     * is the server rubber-banding the client back. Normal updates pass through
     * byte-identical, and a packet too short to parse is forwarded rather than
     * dropped. Mode 2 carries teleportCause/teleportItem, but whether a given
     * teleport is a correction or a real portal exit is not in the bytes, so
     * mode 2 is dropped too — [IMPOSSIBLE] holds the ids that would need that
     * distinction made from history.
     */
    private fun movementCorrection(direction: RelayDirection, packet: ByteArray): List<ByteArray> {
        val rotation = rotationOffset(packet) ?: return listOf(packet)
        val (mode, _) = ModuleWire.readByte(packet, rotation + MODE_OFFSET) ?: return listOf(packet)
        // onGround follows mode; if it is missing the packet is truncated, and a
        // relay never drops a packet it could not fully parse.
        if (ModuleWire.readBool(packet, rotation + ON_GROUND_OFFSET) == null) {
            return listOf(packet)
        }
        return if (mode == MODE_NORMAL) listOf(packet) else emptyList()
    }

    /**
     * speed: scale the horizontal step the client just took. MovePlayer 0x13
     * carries one absolute position, so the step is `position - ctx.self*` — the
     * previous position is the whole reason this is possible at all.
     *
     * Scoped to [RelayDirection.TO_SERVER] because MovePlayer is `bound: both`
     * and the clientbound leg carries *other* entities' positions; multiplying
     * those would move the world, not the player. Vertical is left alone:
     * stretching y is flight, not speed.
     *
     * The first MovePlayer of a session has no predecessor, so it is forwarded
     * untouched and only recorded — it primes the next packet's delta.
     */
    private fun speed(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        val move = parseMove(packet) ?: return listOf(packet)
        val primed = ctx.isPrimed()
        val prevX = ctx.selfX
        val prevZ = ctx.selfZ
        observe(move, ctx)
        if (!primed) return listOf(packet)
        val multiplier = ctx.number(SETTING_SPEED_MULTIPLIER, DEFAULT_SPEED_MULTIPLIER)
        val moved = ModuleWire.put(
            packet,
            move.rotation + X_OFFSET,
            ModuleWire.writeF32LE(prevX + (move.x - prevX) * multiplier),
        )
        return listOf(
            ModuleWire.put(
                moved,
                move.rotation + Z_OFFSET,
                ModuleWire.writeF32LE(prevZ + (move.z - prevZ) * multiplier),
            ),
        )
    }

    /**
     * no_fall: while airborne below the last on-ground height, report that
     * height instead. The fall distance is exactly what the server uses to
     * decide on damage, and `ctx.lastGroundY` is the only place that height
     * exists — a MovePlayer packet carries one y and nothing remembers the last.
     *
     * Nothing is written while the client is on the ground, while it is at or
     * above that height, or before any ground has been seen: those are the
     * un-primed and no-op cases, and both must forward untouched.
     *
     * `internal` rather than private because `xykell.player.no_fall` is the same
     * rule under a second registry name and must not be able to drift from it.
     */
    internal fun noFall(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        val move = parseMove(packet) ?: return listOf(packet)
        val hasGround = ctx.hasGround()
        val lastGround = ctx.lastGroundY
        observe(move, ctx)
        if (move.onGround) return listOf(packet)
        if (!hasGround) return listOf(packet)
        if (move.y >= lastGround) return listOf(packet)
        return listOf(
            ModuleWire.put(
                packet,
                move.rotation + Y_OFFSET,
                ModuleWire.writeF32LE(lastGround),
            ),
        )
    }

    /**
     * slow_falling: cap the downward step at [SLOW_FALLING_MAX_DESCENT] blocks
     * per reported tick. Unlike no_fall this still falls — a server watching the
     * descent sees a slow one — so it is the sane cousin, not a duplicate.
     *
     * Needs the previous y (`ctx.selfY` minus this packet's y is the step) and
     * therefore forwards the first packet of a session untouched. Rising and
     * level packets are left alone: only a descent is clamped.
     */
    private fun slowFalling(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        val move = parseMove(packet) ?: return listOf(packet)
        val primed = ctx.isPrimed()
        val prevY = ctx.selfY
        observe(move, ctx)
        if (!primed) return listOf(packet)
        val descent = prevY - move.y
        if (descent <= 0f) return listOf(packet)
        val cap = ctx.number(SETTING_SLOW_FALLING_MAX_DESCENT, SLOW_FALLING_MAX_DESCENT)
        if (descent <= cap) return listOf(packet)
        return listOf(
            ModuleWire.put(
                packet,
                move.rotation + Y_OFFSET,
                ModuleWire.writeF32LE(prevY - cap),
            ),
        )
    }

    /**
     * fly: hold the reported position up by [FLY_LIFT] every tick and claim the
     * ground was never left. Both halves are needed: the lift alone still
     * reports `on_ground: false`, and the server's own fall-distance accounting
     * then pulls the player back down; the flag alone does not move anything.
     *
     * Absolute per packet, so the lift cannot accumulate. Needs no history, and
     * so behaves identically on a fresh [ModuleContext].
     */
    private fun fly(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        val move = parseMove(packet) ?: return listOf(packet)
        observe(move, ctx)
        val lifted = ModuleWire.put(
            packet,
            move.rotation + Y_OFFSET,
            ModuleWire.writeF32LE(move.y + ctx.number(SETTING_FLY_LIFT, FLY_LIFT)),
        )
        return listOf(ModuleWire.put(lifted, move.rotation + ON_GROUND_OFFSET, byteArrayOf(1)))
    }

    /**
     * motion_fly: fly plus horizontal momentum — the lift and the ground flag of
     * fly, with the same horizontal delta scaling speed does. The lift applies
     * to every packet; the momentum needs the previous position, so the first
     * packet of a session rises without advancing.
     */
    private fun motionFly(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        val move = parseMove(packet) ?: return listOf(packet)
        val primed = ctx.isPrimed()
        val prevX = ctx.selfX
        val prevZ = ctx.selfZ
        observe(move, ctx)
        val momentum = ctx.number(SETTING_MOTION_FLY_MOMENTUM, MOTION_FLY_MOMENTUM)
        val outX = if (primed) prevX + (move.x - prevX) * momentum else move.x
        val outZ = if (primed) prevZ + (move.z - prevZ) * momentum else move.z
        var out = ModuleWire.put(packet, move.rotation + X_OFFSET, ModuleWire.writeF32LE(outX))
        out = ModuleWire.put(out, move.rotation + Z_OFFSET, ModuleWire.writeF32LE(outZ))
        out = ModuleWire.put(
            out,
            move.rotation + Y_OFFSET,
            ModuleWire.writeF32LE(move.y + ctx.number(SETTING_MOTION_FLY_LIFT, MOTION_FLY_LIFT)),
        )
        return listOf(ModuleWire.put(out, move.rotation + ON_GROUND_OFFSET, byteArrayOf(1)))
    }

    /**
     * jump_boost: add [JUMP_BOOST_RISE] on every tick the client reports moving
     * upward. The server infers jump velocity from the positions it is told, so
     * a taller reported ascent is a taller inferred jump.
     *
     * This does *not* create a jump — nothing here injects an impulse. It
     * enlarges an ascent the client already produced, which is why the test
     * asserts both the rising case (boosted) and the falling one (untouched)
     * from the same primed context.
     */
    private fun jumpBoost(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        val move = parseMove(packet) ?: return listOf(packet)
        val primed = ctx.isPrimed()
        val prevY = ctx.selfY
        observe(move, ctx)
        if (!primed) return listOf(packet)
        if (move.y <= prevY) return listOf(packet)
        return listOf(
            ModuleWire.put(
                packet,
                move.rotation + Y_OFFSET,
                ModuleWire.writeF32LE(move.y + ctx.number(SETTING_JUMP_BOOST_RISE, JUMP_BOOST_RISE)),
            ),
        )
    }
}