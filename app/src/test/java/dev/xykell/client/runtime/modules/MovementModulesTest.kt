package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.BedrockPacketIds
import dev.xykell.client.runtime.relay.RelayDirection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-exact tests for the MOVEMENT batch. Every assertion that a transform
 * exists also asserts that its bytes changed, so deleting a transform body
 * fails the suite rather than passing a pass-through.
 *
 * The stateful transforms (speed, no_fall, slow_falling, motion_fly,
 * jump_boost) are tested as a *sequence*: a first packet primes [ModuleContext],
 * a second packet is measured against it. Each also asserts that a fresh
 * [ModuleContext] forwards untouched, which is what proves the behaviour came
 * from the state and not from a constant.
 */
class MovementModulesTest {

    /** Tolerance for float comparisons; the wire values are single-precision. */
    private val TOLERANCE = 0.0001

    private val allIds = listOf(
        "xykell.movement.sprint",
        "xykell.movement.auto_sprint",
        "xykell.movement.speed",
        "xykell.movement.no_slow",
        "xykell.movement.step",
        "xykell.movement.air_jump",
        "xykell.movement.fly",
        "xykell.movement.motion_fly",
        "xykell.movement.jetpack",
        "xykell.movement.phase",
        "xykell.movement.glide",
        "xykell.movement.auto_elytra",
        "xykell.movement.auto_rocket",
        "xykell.movement.bunny_hop",
        "xykell.movement.jump_boost",
        "xykell.movement.levitate",
        "xykell.movement.slow_falling",
        "xykell.movement.water_walk",
        "xykell.movement.tap_tp",
        "xykell.movement.safe_walk",
        "xykell.movement.no_fall",
        "xykell.movement.movement_correction",
        "xykell.movement.timer",
        "xykell.movement.ladder_fly",
        "xykell.movement.block_fly",
    )

    /** Little-endian signed int, needed only for the mode-2 teleport fields. */
    private fun leInt(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v ushr 8) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(),
        ((v ushr 24) and 0xff).toByte(),
    )

    /**
     * MovePlayer 0x13 in pmmp field order. Runtime/riding/tick ids stay small
     * so `ModuleWire.writeVarUInt` encodes them exactly like the varulong the
     * protocol wants.
     */
    private fun move(
        runtimeId: Int = 7,
        x: Float = 10f,
        y: Float = 64f,
        z: Float = -3f,
        pitch: Float = 5f,
        yaw: Float = 90f,
        headYaw: Float = 95f,
        mode: Int = 0,
        onGround: Boolean = true,
        cause: Int = 0,
        item: Int = 0,
        tick: Int = 42,
    ): ByteArray = ModuleWire.build(
        MovementModules.MOVE_PLAYER,
        ModuleWire.writeVarUInt(runtimeId),
        ModuleWire.writeF32LE(x),
        ModuleWire.writeF32LE(y),
        ModuleWire.writeF32LE(z),
        ModuleWire.writeF32LE(pitch),
        ModuleWire.writeF32LE(yaw),
        ModuleWire.writeF32LE(headYaw),
        byteArrayOf(mode.toByte()),
        byteArrayOf(if (onGround) 1 else 0),
        ModuleWire.writeVarUInt(0),
        *(if (mode == 2) arrayOf(leInt(cause), leInt(item)) else emptyArray()),
        ModuleWire.writeVarUInt(tick),
    )

    /** SetHealth 0x2A: a non-move packet with a different header id. */
    private fun health(hp: Int = 20): ByteArray =
        ModuleWire.build(0x2A, ModuleWire.writeVarInt(hp))

    private fun strings(packets: List<ByteArray>): List<String> =
        packets.map { it.contentToString() }

    /** Offset of the y float: header, the runtime-id varuint, then x. */
    private fun yOffset(raw: ByteArray): Int {
        val body = requireNotNull(ModuleWire.bodyStart(raw))
        return body + requireNotNull(ModuleWire.varUIntSize(raw, body)) + 4
    }

    /** Offset of the x float, the float immediately before y. */
    private fun xOffset(raw: ByteArray): Int = yOffset(raw) - 4

    /** Offset of the z float, the float immediately after y. */
    private fun zOffset(raw: ByteArray): Int = yOffset(raw) + 4

    /** Offset of the on_ground byte, 25 bytes past the x float. */
    private fun onGroundOffset(raw: ByteArray): Int = xOffset(raw) + 25

    private fun xAt(raw: ByteArray): Float = requireNotNull(ModuleWire.readF32LE(raw, xOffset(raw))).first
    private fun yAt(raw: ByteArray): Float = requireNotNull(ModuleWire.readF32LE(raw, yOffset(raw))).first
    private fun zAt(raw: ByteArray): Float = requireNotNull(ModuleWire.readF32LE(raw, zOffset(raw))).first

    /**
     * Float equality with a tolerance. Kotlin will not widen a `Float` argument
     * into JUnit's `double` overload, so the conversion is explicit here rather
     * than at every call site.
     */
    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertEquals(message, expected.toDouble(), actual.toDouble(), TOLERANCE)
    }

    private fun out(id: String, raw: ByteArray, ctx: ModuleContext): ByteArray =
        requireNotNull(MovementModules.transform(id, RelayDirection.TO_SERVER, raw, ctx).firstOrNull())

    /**
     * Run [packets] through [id] on one shared context and return every result,
     * which is how the stateful transforms are meant to be driven.
     */
    private fun sequence(id: String, ctx: ModuleContext, vararg packets: ByteArray): List<ByteArray> =
        packets.map { out(id, it, ctx) }

    // ------------------------------------------------------------- registry shape

    @Test
    fun registryPartitionsEveryMovementIdExactlyOnce() {
        assertEquals(25, allIds.size)
        assertEquals(allIds.toSet(), MovementModules.IMPLEMENTED + MovementModules.IMPOSSIBLE.keys)
        val overlap = MovementModules.IMPLEMENTED.intersect(MovementModules.IMPOSSIBLE.keys)
        assertTrue("ids in both sets: $overlap", overlap.isEmpty())
    }

    @Test
    fun everyImpossibleIdCarriesAConcreteReason() {
        assertEquals(17, MovementModules.IMPOSSIBLE.size)
        for ((id, reason) in MovementModules.IMPOSSIBLE) {
            assertTrue("$id has no reason", reason.length > 30)
            assertTrue("$id reason is not a sentence", reason.endsWith("."))
        }
    }

    @Test
    fun movePlayerIdComesFromTheGeneratedTableNotFromMemory() {
        // BedrockPacketIds exists because a hand-written table drifted and made
        // a module filter the wrong id, which silently does nothing at all.
        assertEquals(BedrockPacketIds.infoOf("MovePlayer")!!.id, MovementModules.MOVE_PLAYER)
        assertEquals(0x13, MovementModules.MOVE_PLAYER)
        // MovePlayer is `bound: both`, which is why every module below that
        // rewrites a position is scoped to the outbound leg.
        val info = BedrockPacketIds.infoOf("MovePlayer")!!
        assertTrue(info.toClient)
        assertTrue(info.toServer)
    }

    /** The ids whose behaviour depends on a previous packet, and so on [ModuleContext]. */
    private val statefulIds = listOf(
        "xykell.movement.speed",
        "xykell.movement.no_fall",
        "xykell.movement.slow_falling",
        "xykell.movement.motion_fly",
        "xykell.movement.jump_boost",
    )

    /**
     * The subset that measures *only* a delta, and so must be a complete no-op
     * before any packet has primed [ModuleContext]. `motion_fly` is excluded on
     * purpose: its lift is absolute and applies to every packet, while only its
     * momentum needs a predecessor.
     */
    private val deltaIds = listOf(
        "xykell.movement.speed",
        "xykell.movement.no_fall",
        "xykell.movement.slow_falling",
        "xykell.movement.jump_boost",
    )

    @Test
    fun unknownAndImpossibleIdsForwardUntouched() {
        val raw = move()
        for (id in listOf("xykell.movement.levitate_typo") + MovementModules.IMPOSSIBLE.keys) {
            val out = MovementModules.transform(id, RelayDirection.TO_CLIENT, raw)
            assertEquals(id, 1, out.size)
            assertArrayEquals(id, raw, out[0])
        }
    }

    @Test
    fun everyDeltaIdForwardsUntouchedOnAFreshContext() {
        // The un-primed half of the stateful contract: with no previous packet
        // there is nothing to measure against, so these must be exact no-ops.
        for (id in deltaIds) {
            val falling = move(x = 10f, y = 40f, onGround = false)
            assertArrayEquals(
                "$id rewrote a fresh-context packet",
                falling,
                MovementModules.transform(id, RelayDirection.TO_SERVER, falling).first(),
            )
        }
    }

    // -------------------------------------------------------------------- levitate

    @Test
    fun levitate_raisesYByTheLiftAndTouchesNothingElse() {
        val raw = move(y = 64f)
        val out = MovementModules.transform("xykell.movement.levitate", RelayDirection.TO_CLIENT, raw)
        assertEquals(1, out.size)
        val rewritten = out[0]
        assertEquals(raw.size, rewritten.size)
        val at = yOffset(raw)
        assertEquals(
            64f + MovementModules.LEVITATE_LIFT,
            requireNotNull(ModuleWire.readF32LE(rewritten, at)).first,
            0.0001f,
        )
        // every byte outside the y float is unchanged, and every byte inside it changed
        assertArrayEquals(
            raw.copyOfRange(0, at),
            rewritten.copyOfRange(0, at),
        )
        assertArrayEquals(
            raw.copyOfRange(at + 4, raw.size),
            rewritten.copyOfRange(at + 4, rewritten.size),
        )
        assertFalse(
            "y float was not rewritten",
            raw.copyOfRange(at, at + 4).contentEquals(rewritten.copyOfRange(at, at + 4)),
        )
    }

    @Test
    fun levitate_isAbsoluteSoRepeatedCallsDoNotAccumulate() {
        val first = move(y = 100f)
        val second = move(y = 100f)
        // Each MovePlayer carries an absolute y, so a second wire packet at the
        // same height gets the same lift — the module holds no offset of its own.
        assertArrayEquals(
            MovementModules.transform("xykell.movement.levitate", RelayDirection.TO_CLIENT, first)[0],
            MovementModules.transform("xykell.movement.levitate", RelayDirection.TO_CLIENT, second)[0],
        )
    }

    @Test
    fun levitate_handlesMultiByteRuntimeIds() {
        val raw = move(runtimeId = 200000, y = 12f)
        val at = yOffset(raw)
        assertTrue("fixture must use a 3-byte runtime id", at > 3)
        val out = MovementModules.transform("xykell.movement.levitate", RelayDirection.TO_CLIENT, raw)[0]
        assertEquals(
            12f + MovementModules.LEVITATE_LIFT,
            requireNotNull(ModuleWire.readF32LE(out, at)).first,
            0.0001f,
        )
    }

    @Test
    fun levitate_ignoresNonMoveAndTruncatedPackets() {
        val hp = health()
        assertArrayEquals(hp, MovementModules.transform("xykell.movement.levitate", RelayDirection.TO_CLIENT, hp)[0])
        val truncated = move().copyOf(5)
        assertArrayEquals(
            truncated,
            MovementModules.transform("xykell.movement.levitate", RelayDirection.TO_CLIENT, truncated)[0],
        )
        assertArrayEquals(
            ByteArray(0),
            MovementModules.transform("xykell.movement.levitate", RelayDirection.TO_CLIENT, ByteArray(0))[0],
        )
    }

    // ---------------------------------------------------------- movement_correction

    @Test
    fun movementCorrection_dropsResetModes() {
        for (mode in listOf(1, 2, 3, 9)) {
            val out = MovementModules.transform("xykell.movement.movement_correction", RelayDirection.TO_CLIENT, move(mode = mode))
            assertTrue("mode $mode should be dropped", out.isEmpty())
        }
    }

    @Test
    fun movementCorrection_keepsNormalUpdatesByteIdentical() {
        val raw = move(mode = 0, y = 70f, onGround = false)
        assertEquals(strings(listOf(raw)), strings(MovementModules.transform("xykell.movement.movement_correction", RelayDirection.TO_CLIENT, raw)))
    }

    @Test
    fun movementCorrection_stillDropsATeleportModeWithItsCauseFields() {
        val raw = move(mode = 2, cause = 3, item = 1)
        assertTrue(MovementModules.transform("xykell.movement.movement_correction", RelayDirection.TO_CLIENT, raw).isEmpty())
    }

    @Test
    fun movementCorrection_forwardsWhenModeOrOnGroundIsMissing() {
        val full = move(mode = 1)
        val body = requireNotNull(ModuleWire.bodyStart(full))
        // cut after mode, before onGround: parseable mode, missing flag
        val beforeOnGround = full.copyOfRange(0, body + 26)
        assertArrayEquals(
            beforeOnGround,
            MovementModules.transform("xykell.movement.movement_correction", RelayDirection.TO_CLIENT, beforeOnGround)[0],
        )
        val beforeMode = full.copyOfRange(0, body + 25)
        assertArrayEquals(
            beforeMode,
            MovementModules.transform("xykell.movement.movement_correction", RelayDirection.TO_CLIENT, beforeMode)[0],
        )
    }

    @Test
    fun movementCorrection_ignoresNonMovePackets() {
        val hp = health(3)
        assertArrayEquals(hp, MovementModules.transform("xykell.movement.movement_correction", RelayDirection.TO_CLIENT, hp)[0])
    }

    // --------------------------------------------------------------------- speed

    @Test
    fun speed_scalesTheStepFromThePreviousPacketAndLeavesYAlone() {
        val ctx = ModuleContext().apply {
            settings[MovementModules.SETTING_SPEED_MULTIPLIER] = "2.0"
        }
        sequence("xykell.movement.speed", ctx, move(x = 0f, z = 0f, y = 64f))
        val second = move(x = 10f, z = -4f, y = 64f)
        val rewritten = out("xykell.movement.speed", second, ctx)
        assertClose(20f, xAt(rewritten), "x delta was not doubled")
        assertClose(-8f, zAt(rewritten), "z delta was not doubled")
        assertClose(64f, yAt(rewritten), "speed must not scale y")
        // "must not scale y" means y must come through BYTE IDENTICAL.
        assertTrue(
            "the y float was altered by a horizontal-only speed multiplier",
            second.copyOfRange(yOffset(second), yOffset(second) + 4)
                .contentEquals(rewritten.copyOfRange(yOffset(rewritten), yOffset(rewritten) + 4)),
        )
    }

    @Test
    fun speed_isRelativeToTheLastPacketNotToTheOrigin() {
        // Two packets 5 blocks apart at multiplier 2 must land 10 blocks from the
        // *previous* packet, proving the delta came from ctx and not from 0,0,0.
        val ctx = ModuleContext().apply {
            settings[MovementModules.SETTING_SPEED_MULTIPLIER] = "2.0"
        }
        sequence("xykell.movement.speed", ctx, move(x = 100f, z = 50f))
        val rewritten = out("xykell.movement.speed", move(x = 105f, z = 50f), ctx)
        assertEquals(110f, xAt(rewritten), 0.0001f)
        assertEquals(50f, zAt(rewritten), 0.0001f)
    }

    @Test
    fun speed_recordsTheClientsOwnPositionNotTheRewrite() {
        // If the module fed its own lie back into ctx the error would compound;
        // ctx must always hold what the client actually reported.
        val ctx = ModuleContext().apply {
            settings[MovementModules.SETTING_SPEED_MULTIPLIER] = "4.0"
        }
        sequence("xykell.movement.speed", ctx, move(x = 0f))
        out("xykell.movement.speed", move(x = 10f), ctx)
        assertClose(10f, ctx.selfX, "ctx must track the client, not the rewrite")
    }

    @Test
    fun speed_ignoresTheClientboundLegAndNonMovePackets() {
        val ctx = ModuleContext()
        sequence("xykell.movement.speed", ctx, move(x = 0f))
        val second = move(x = 10f)
        assertArrayEquals(
            second,
            MovementModules.transform(
                "xykell.movement.speed", RelayDirection.TO_CLIENT, second, ctx,
            )[0],
        )
        val hp = health()
        assertArrayEquals(
            hp,
            MovementModules.transform("xykell.movement.speed", RelayDirection.TO_SERVER, hp, ctx)[0],
        )
    }

    // ------------------------------------------------------------------- no_fall

    @Test
    fun noFall_resendsTheLastGroundHeightWhileFalling() {
        val ctx = ModuleContext()
        sequence("xykell.movement.no_fall", ctx, move(y = 64f, onGround = true))
        val falling = move(y = 58.5f, onGround = false)
        val rewritten = out("xykell.movement.no_fall", falling, ctx)
        assertClose(64f, yAt(rewritten), "fall must be pinned to lastGroundY")
        assertClose(10f, xAt(rewritten), "no_fall must not touch x")
    }

    @Test
    fun noFall_keepsTheSameGroundHeightAcrossADeepFall() {
        val ctx = ModuleContext()
        sequence("xykell.movement.no_fall", ctx, move(y = 64f, onGround = true))
        // A second, deeper fall is measured against the *same* ground height:
        // the packet the client sent while airborne never becomes the new ground.
        val deeper = move(y = 40f, onGround = false)
        assertEquals(64f, yAt(out("xykell.movement.no_fall", deeper, ctx)), 0.0001f)
        assertEquals(64f, ctx.lastGroundY, 0.0001f)
    }

    @Test
    fun noFall_leavesRisingLevelAndGroundedPacketsAlone() {
        val ctx = ModuleContext()
        sequence("xykell.movement.no_fall", ctx, move(y = 64f, onGround = true))
        for (candidate in listOf(move(y = 70f, onGround = false), move(y = 64f, onGround = false), move(y = 64f, onGround = true))) {
            assertArrayEquals(
                "y=${yAt(candidate)} onGround=${candidate[onGroundOffset(candidate)].toInt()}",
                candidate,
                MovementModules.transform("xykell.movement.no_fall", RelayDirection.TO_SERVER, candidate, ctx)[0],
            )
        }
    }

    // ------------------------------------------------------------ slow_falling

    @Test
    fun slowFalling_capsTheDownwardStepButStillFalls() {
        val ctx = ModuleContext()
        sequence("xykell.movement.slow_falling", ctx, move(y = 64f))
        val rewritten = out("xykell.movement.slow_falling", move(y = 63f), ctx)
        assertClose(
            64f - MovementModules.SLOW_FALLING_MAX_DESCENT,
            yAt(rewritten),
            "descent was not capped",
        )
        assertTrue(
            "slow_falling must still descend, unlike no_fall",
            yAt(rewritten) < 64f,
        )
    }

    @Test
    fun slowFalling_leavesAStepWithinTheCapAndEveryAscent() {
        val ctx = ModuleContext()
        sequence("xykell.movement.slow_falling", ctx, move(y = 64f))
        val gentle = move(y = 64f - MovementModules.SLOW_FALLING_MAX_DESCENT / 2f)
        assertArrayEquals(gentle, MovementModules.transform("xykell.movement.slow_falling", RelayDirection.TO_SERVER, gentle, ctx)[0])
        val rising = move(y = 70f)
        assertArrayEquals(rising, MovementModules.transform("xykell.movement.slow_falling", RelayDirection.TO_SERVER, rising, ctx)[0])
    }

    // ---------------------------------------------------------------------- fly

    @Test
    fun fly_liftsYAndForcesOnGround() {
        val raw = move(y = 64f, onGround = false)
        val rewritten = out("xykell.movement.fly", raw, ModuleContext())
        assertEquals(64f + MovementModules.FLY_LIFT, yAt(rewritten), 0.0001f)
        assertEquals("on_ground must be forced true", 1, rewritten[onGroundOffset(rewritten)].toInt())
        assertEquals("fixture must claim to be airborne", 0, raw[onGroundOffset(raw)].toInt())
    }

    @Test
    fun fly_touchesNothingButTheYFloatAndTheGroundByte() {
        val raw = move(y = 64f, onGround = false)
        val rewritten = out("xykell.movement.fly", raw, ModuleContext())
        assertEquals(raw.size, rewritten.size)
        val y = yOffset(raw)
        assertArrayEquals(raw.copyOfRange(0, y), rewritten.copyOfRange(0, y))
        // x, z, pitch, yaw, headYaw and mode all sit in the untouched window
        val ground = onGroundOffset(raw)
        assertArrayEquals(raw.copyOfRange(y + 4, ground), rewritten.copyOfRange(y + 4, ground))
        assertArrayEquals(raw.copyOfRange(ground + 1, raw.size), rewritten.copyOfRange(ground + 1, rewritten.size))
    }

    @Test
    fun fly_doesNotAccumulateAndIgnoresTheClientboundLeg() {
        val once = out("xykell.movement.fly", move(y = 64f), ModuleContext())
        val twice = out("xykell.movement.fly", move(y = 64f), ModuleContext())
        assertArrayEquals(once, twice)
        val raw = move(y = 64f)
        assertArrayEquals(
            raw,
            MovementModules.transform("xykell.movement.fly", RelayDirection.TO_CLIENT, raw, ModuleContext())[0],
        )
    }

    // -------------------------------------------------------------- motion_fly

    @Test
    fun motionFly_liftsOnEveryPacketButScalesMomentumOnlyOncePrimed() {
        // First packet: lift applies, momentum has no predecessor to scale.
        val fresh = ModuleContext()
        val first = move(x = 0f, z = 0f, y = 64f)
        val firstOut = out("xykell.movement.motion_fly", first, fresh)
        assertClose(0f, xAt(firstOut), "un-primed x must not move")
        assertEquals(64f + MovementModules.MOTION_FLY_LIFT, yAt(firstOut), 0.0001f)

        val second = move(x = 10f, z = 0f, y = 64f)
        val secondOut = out("xykell.movement.motion_fly", second, fresh)
        assertClose(
            10f * MovementModules.MOTION_FLY_MOMENTUM,
            xAt(secondOut),
            "primed x must carry momentum",
        )
        assertEquals(1, secondOut[onGroundOffset(secondOut)].toInt())
    }

    @Test
    fun motionFly_isNotTheSameTransformAsFlyOrSpeed() {
        val ctx = ModuleContext()
        sequence("xykell.movement.motion_fly", ctx, move(x = 0f, y = 64f))
        val second = move(x = 10f, y = 64f)
        val motion = out("xykell.movement.motion_fly", second, ctx)
        val fly = out("xykell.movement.fly", second, ModuleContext())
        assertFalse(
            "motion_fly must not be fly with a different constant",
            motion.contentEquals(fly),
        )
        assertClose(10f, xAt(fly), "fly must not scale x")
    }

    // -------------------------------------------------------------- jump_boost

    @Test
    fun jumpBoost_addsRiseWhileAscendingAndNothingWhileDescending() {
        val ctx = ModuleContext()
        sequence("xykell.movement.jump_boost", ctx, move(y = 64f, onGround = true))
        val rising = move(y = 64.5f, onGround = false)
        val boosted = out("xykell.movement.jump_boost", rising, ctx)
        assertEquals(64.5f + MovementModules.JUMP_BOOST_RISE, yAt(boosted), 0.0001f)

        // Same primed context, now falling: the boost must not fire.
        val falling = move(y = 63f, onGround = false)
        assertArrayEquals(
            falling,
            MovementModules.transform("xykell.movement.jump_boost", RelayDirection.TO_SERVER, falling, ctx)[0],
        )
    }

    // ------------------------------------------------------------------ shared

    @Test
    fun statefulModulesForwardTruncatedAndEmptyPackets() {
        val truncated = move(y = 64f).copyOf(8)
        for (id in statefulIds) {
            for (raw in listOf(truncated, ByteArray(0), byteArrayOf(0x80.toByte()))) {
                assertArrayEquals(
                    id,
                    raw,
                    MovementModules.transform(id, RelayDirection.TO_SERVER, raw, ModuleContext())[0],
                )
            }
        }
    }

    @Test
    fun everyImplementedIdChangesBytesOnAPrimedContext() {
        // A pass-through in IMPLEMENTED would make the set a lie, so each id is
        // driven with a context primed by its own kind of packet and the result
        // must differ from what went in.
        val cases = mapOf(
            "xykell.movement.speed" to (move(x = 0f) to move(x = 10f)),
            "xykell.movement.no_fall" to (move(y = 64f, onGround = true) to move(y = 55f, onGround = false)),
            "xykell.movement.slow_falling" to (move(y = 64f) to move(y = 60f)),
            "xykell.movement.fly" to (move(y = 64f, onGround = false) to move(y = 64f, onGround = false)),
            "xykell.movement.motion_fly" to (move(x = 0f, y = 64f) to move(x = 10f, y = 64f)),
            "xykell.movement.jump_boost" to (move(y = 64f) to move(y = 66f)),
        )
        for (id in cases.keys) {
            val (primer, subject) = cases.getValue(id)
            val ctx = ModuleContext()
            sequence(id, ctx, primer)
            val rewritten = out(id, subject, ctx)
            assertFalse(
                "id $id forwards unchanged but is listed as implemented",
                rewritten.contentEquals(subject),
            )
        }
    }

    @Test
    fun bothImplementedIdsAreReachableAndNeitherIsAPassThrough() {
        val normal = move(mode = 0)
        val reset = move(mode = 1)
        assertTrue(MovementModules.transform("xykell.movement.levitate", RelayDirection.TO_CLIENT, normal)[0] != normal)
        assertTrue(MovementModules.transform("xykell.movement.movement_correction", RelayDirection.TO_CLIENT, reset).isEmpty())
        assertEquals(8, MovementModules.IMPLEMENTED.size)
    }
}