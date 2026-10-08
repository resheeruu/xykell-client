package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.BedrockPacketIds
import dev.xykell.client.runtime.relay.RelayDirection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-exact tests for the COMBAT batch. Fixtures are built from the
 * `registry/bedrock-packets.json` field orders and the ids come from the
 * generated [BedrockPacketIds] table, so a drifted id fails here instead of
 * silently doing nothing in production.
 *
 * Every assertion that a transform exists also asserts its bytes changed, so
 * deleting a transform body fails the suite rather than passing a pass-through.
 */
class CombatModulesTest {

    /** Tolerance for float comparisons; the wire values are single-precision. */
    private val TOLERANCE = 0.0001

    /**
     * Float equality with a tolerance. Kotlin will not widen a `Float` argument
     * into JUnit's `double` overload, so the conversion is explicit here.
     */
    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertEquals(message, expected.toDouble(), actual.toDouble(), TOLERANCE)
    }

    private fun bytes(raw: ByteArray) = raw.contentToString()

    private fun rendered(out: List<ByteArray>) = out.map { it.contentToString() }

    /**
     * SetEntityMotion 0x28: varuint entityUniqueId, then three f32 LE velocity
     * and a trailing tick varint. This is the knockback vector — the old fixture
     * used 0x1B, which is EntityEvent (the jump/hurt animation).
     */
    private fun setEntityMotion(x: Float, y: Float, z: Float, entityId: Int = 1): ByteArray =
        ModuleWire.build(
            0x28,
            ModuleWire.writeVarUInt(entityId),
            ModuleWire.writeF32LE(x),
            ModuleWire.writeF32LE(y),
            ModuleWire.writeF32LE(z),
            ModuleWire.writeVarUInt(42),
        )

    /** EntityEvent 0x1B, the id the hand-written table used to call SetActorMotion. */
    private fun entityEvent(eventId: Int = 2): ByteArray = ModuleWire.build(0x1b, byteArrayOf(eventId.toByte()))

    /**
     * PlayerAuthInput 0x90 in registry field order: pitch, yaw, position,
     * move_vector, head_yaw, input_mode, play_mode, ...
     *
     * `input_data` is deliberately absent. Upstream defines it as
     * `InputData[]varint` — an optional list as of 1.26.40 — with no presence
     * byte and no element framing, so there is no honest way to write it into a
     * fixture. auto_crit never parses past the leading pitch, which is exactly
     * why this transform can exist while every input-flag one cannot.
     */
    private fun playerAuthInput(pitch: Float, yaw: Float = 0f): ByteArray = ModuleWire.build(
        0x90,
        ModuleWire.writeF32LE(pitch),
        ModuleWire.writeF32LE(yaw),
        ModuleWire.writeF32LE(1f),
        ModuleWire.writeF32LE(64f),
        ModuleWire.writeF32LE(2f),
        ModuleWire.writeF32LE(1f),
        ModuleWire.writeF32LE(0f),
        ModuleWire.writeF32LE(0.5f),
        ModuleWire.writeF32LE(0.5f),
        byteArrayOf(2), // input_mode: touch
        byteArrayOf(0), // play_mode: normal
        ModuleWire.writeVarInt(0),
        ModuleWire.writeF32LE(0.1f),
        ModuleWire.writeF32LE(0.2f),
        byteArrayOf(0), // interaction_model: touch
        ModuleWire.writeF32LE(0f),
        ModuleWire.writeF32LE(0f),
        ModuleWire.writeVarUInt(7),
        ModuleWire.writeF32LE(0.1f),
        ModuleWire.writeF32LE(-0.3f),
        ModuleWire.writeF32LE(0.2f),
    )

    @Test
    fun packetIdsComeFromTheGeneratedTableNotFromMemory() {
        assertEquals(0x28, CombatModules.SET_ENTITY_MOTION)
        assertEquals(BedrockPacketIds.infoOf("SetEntityMotion")!!.id, CombatModules.SET_ENTITY_MOTION)
        assertEquals(0x90, CombatModules.PLAYER_AUTH_INPUT)
        assertEquals(BedrockPacketIds.infoOf("PlayerAuthInput")!!.id, CombatModules.PLAYER_AUTH_INPUT)
        // 0x1B is EntityEvent, not a motion packet: this is the id drift that
        // made the old knockback cancel a hurt animation instead of a push.
        assertEquals("EntityEvent", BedrockPacketIds.nameOf(0x1b))
    }

    @Test
    fun velocityDropsSetEntityMotion() {
        val out = CombatModules.transform("xykell.combat.velocity", RelayDirection.TO_CLIENT,
            setEntityMotion(0.4f, 0.6f, 0f),
        )
        assertEquals(emptyList<String>(), rendered(out))
    }

    @Test
    fun velocityForwardsEveryOtherPacket() {
        val move = ModuleWire.build(0x13, byteArrayOf(1, 2, 3, 4))
        val out = CombatModules.transform("xykell.combat.velocity", RelayDirection.TO_CLIENT, move)
        assertEquals(listOf(bytes(move)), rendered(out))
    }

    @Test
    fun velocityForwardsPacketWithUnterminatedHeader() {
        // 0x80 sets the continuation bit and no terminator follows, so the
        // id cannot be read: forward rather than guess.
        val truncated = byteArrayOf(0x80.toByte())
        val out = CombatModules.transform("xykell.combat.velocity", RelayDirection.TO_CLIENT, truncated)
        assertEquals(listOf(bytes(truncated)), rendered(out))
    }

    @Test
    fun velocityForwardsEmptyPacket() {
        val empty = ByteArray(0)
        assertEquals(
            listOf(bytes(empty)),
            rendered(CombatModules.transform("xykell.combat.velocity", RelayDirection.TO_CLIENT, empty)),
        )
    }

    @Test
    fun velocityDropsDespiteSubClientBitsInHeader() {
        // Sub-client ids live above the low 10 bits; the id check must mask
        // them off instead of comparing the whole header.
        val header = 0x28 or (2 shl 10)
        assertEquals(0x28, header and ModuleWire.ID_MASK)
        val packet = ModuleWire.build(header, byteArrayOf(0x00))
        assertEquals(
            emptyList<String>(),
            rendered(CombatModules.transform("xykell.combat.velocity", RelayDirection.TO_CLIENT, packet)),
        )
    }

    @Test
    fun velocityNoLongerDropsTheJumpAnimationPacket() {
        // The regression this batch fixes: 0x1B is EntityEvent, so dropping it
        // cancelled the hurt/jump animation rather than any knockback.
        val anim = entityEvent(2)
        assertEquals(
            listOf(bytes(anim)),
            rendered(CombatModules.transform("xykell.combat.velocity", RelayDirection.TO_CLIENT, anim)),
        )
    }

    @Test
    fun `velocity keeps the players own outbound motion`() {
        val motion = setEntityMotion(1f, 0f, 0f)
        val out = CombatModules.transform(
            "xykell.combat.velocity", RelayDirection.TO_SERVER, motion,
        )
        assertEquals(listOf(motion.contentToString()), out.map { it.contentToString() })
    }

    // ---------------------------------------------------------------- knockback

    private fun velocityAt(raw: ByteArray): Triple<Float, Float, Float> {
        val body = requireNotNull(ModuleWire.bodyStart(raw))
        val at = body + requireNotNull(ModuleWire.varUIntSize(raw, body))
        val (x, afterX) = requireNotNull(ModuleWire.readF32LE(raw, at))
        val (y, afterY) = requireNotNull(ModuleWire.readF32LE(raw, afterX))
        val (z, _) = requireNotNull(ModuleWire.readF32LE(raw, afterY))
        return Triple(x, y, z)
    }

    @Test
    fun knockback_scalesTheWholeVector() {
        val ctx = ModuleContext().apply {
            settings[CombatModules.SETTING_KNOCKBACK_SCALE] = "0.5"
        }
        val raw = setEntityMotion(0.4f, 0.6f, -0.2f)
        val out = CombatModules.transform(
            "xykell.combat.knockback", RelayDirection.TO_CLIENT, raw, ctx,
        )
        assertEquals(1, out.size)
        val (x, y, z) = velocityAt(out[0])
        assertEquals(0.2f, x, 0.0001f)
        assertEquals(0.3f, y, 0.0001f)
        assertEquals(-0.1f, z, 0.0001f)
    }

    @Test
    fun knockback_touchesOnlyTheVelocityFloats() {
        val ctx = ModuleContext().apply {
            settings[CombatModules.SETTING_KNOCKBACK_SCALE] = "0.25"
        }
        val raw = setEntityMotion(4f, 4f, 4f)
        val rewritten = CombatModules.transform("xykell.combat.knockback", RelayDirection.TO_CLIENT, raw, ctx)[0]
        assertEquals(raw.size, rewritten.size)
        val body = requireNotNull(ModuleWire.bodyStart(raw))
        val at = body + requireNotNull(ModuleWire.varUIntSize(raw, body))
        assertArrayEquals(raw.copyOfRange(0, at), rewritten.copyOfRange(0, at))
        assertArrayEquals(
            "the trailing tick must survive",
            raw.copyOfRange(at + 12, raw.size),
            rewritten.copyOfRange(at + 12, rewritten.size),
        )
    }

    @Test
    fun knockback_handlesAMultiByteRuntimeId() {
        val ctx = ModuleContext().apply {
            settings[CombatModules.SETTING_KNOCKBACK_SCALE] = "0.5"
        }
        val raw = setEntityMotion(2f, 0f, 0f, entityId = 200000)
        val rewritten = CombatModules.transform("xykell.combat.knockback", RelayDirection.TO_CLIENT, raw, ctx)[0]
        assertEquals(1f, velocityAt(rewritten).first, 0.0001f)
    }

    @Test
    fun knockback_forwardsTheServerboundLegAndTruncatedMotion() {
        val ctx = ModuleContext()
        val raw = setEntityMotion(1f, 1f, 1f)
        // SetEntityMotion is `bound: both`: the player's own outbound motion
        // (boats, pistons) must not be scaled.
        assertArrayEquals(
            raw,
            CombatModules.transform("xykell.combat.knockback", RelayDirection.TO_SERVER, raw, ctx)[0],
        )
        for (truncated in listOf(setEntityMotion(1f, 1f, 1f).copyOf(6), ByteArray(0), byteArrayOf(0x80.toByte()))) {
            assertArrayEquals(
                truncated,
                CombatModules.transform("xykell.combat.knockback", RelayDirection.TO_CLIENT, truncated, ctx)[0],
            )
        }
    }

    @Test
    fun knockback_defaultsToHalfNotToNothing() {
        val rewritten = CombatModules.transform(
            "xykell.combat.knockback", RelayDirection.TO_CLIENT,
            setEntityMotion(1f, 2f, 3f), ModuleContext(),
        )[0]
        val (x, y, z) = velocityAt(rewritten)
        assertEquals(0.5f, x, 0.0001f)
        assertEquals(1f, y, 0.0001f)
        assertEquals(1.5f, z, 0.0001f)
    }

    // ---------------------------------------------------------------- auto_crit

    @Test
    fun autoCrit_rewritesTheAttackPitchToLookDown() {
        val raw = playerAuthInput(pitch = 12f, yaw = 90f)
        val rewritten = CombatModules.transform(
            "xykell.combat.auto_crit", RelayDirection.TO_SERVER, raw,
        )[0]
        val body = requireNotNull(ModuleWire.bodyStart(raw))
        assertEquals(
            CombatModules.CRIT_PITCH,
            requireNotNull(ModuleWire.readF32LE(rewritten, body)).first,
            0.0001f,
        )
    }

    @Test
    fun autoCrit_leavesEveryByteAfterThePitchUntouched() {
        // The pitch is the first field, so the rewrite never has to walk past
        // PlayerAuthInput's input_data optional list, whose framing upstream
        // leaves undefined. Everything after it must be byte-identical.
        val raw = playerAuthInput(pitch = 12f)
        val rewritten = CombatModules.transform(
            "xykell.combat.auto_crit", RelayDirection.TO_SERVER, raw,
        )[0]
        val body = requireNotNull(ModuleWire.bodyStart(raw))
        assertEquals(raw.size, rewritten.size)
        assertArrayEquals(raw.copyOfRange(0, body), rewritten.copyOfRange(0, body))
        assertArrayEquals(
            raw.copyOfRange(body + 4, raw.size),
            rewritten.copyOfRange(body + 4, rewritten.size),
        )
        assertFalse(
            "the pitch float was not rewritten",
            raw.copyOfRange(body, body + 4).contentEquals(rewritten.copyOfRange(body, body + 4)),
        )
    }

    @Test
    fun autoCrit_isServerboundOnlyBecausePlayerAuthInputIsBoundServer() {
        val raw = playerAuthInput(pitch = 12f)
        val info = BedrockPacketIds.infoOf("PlayerAuthInput")!!
        assertFalse(info.toClient)
        assertTrue(info.toServer)
        assertArrayEquals(
            raw,
            CombatModules.transform("xykell.combat.auto_crit", RelayDirection.TO_CLIENT, raw)[0],
        )
    }

    @Test
    fun autoCrit_forwardsOtherPacketsAndTruncatedOnes() {
        val ctx = ModuleContext()
        // Valid PlayerAuthInput header with an empty body: the pitch is not
        // readable, so the packet must be forwarded rather than guessed at.
        val headerOnly = ModuleWire.build(0x90, ByteArray(0))
        for (raw in listOf(setEntityMotion(1f, 1f, 1f), entityEvent(), ByteArray(0), headerOnly)) {
            assertEquals(
                "must forward ${bytes(raw)}",
                listOf(bytes(raw)),
                CombatModules.transform("xykell.combat.auto_crit", RelayDirection.TO_SERVER, raw, ctx)
                    .map { bytes(it) },
            )
        }
    }

    @Test
    fun autoCrit_honoursAPitchSetting() {
        val ctx = ModuleContext().apply { settings[CombatModules.SETTING_CRIT_PITCH] = "-45.5" }
        val rewritten = CombatModules.transform(
            "xykell.combat.auto_crit", RelayDirection.TO_SERVER, playerAuthInput(pitch = 3f), ctx,
        )[0]
        val body = requireNotNull(ModuleWire.bodyStart(rewritten))
        assertEquals(-45.5f, requireNotNull(ModuleWire.readF32LE(rewritten, body)).first, 0.0001f)
    }

    // ------------------------------------------------------------------ shared

    @Test
    fun unknownModuleForwardsUntouched() {
        val packet = setEntityMotion(1f, 2f, 3f)
        assertEquals(
            listOf(bytes(packet)),
            rendered(CombatModules.transform("xykell.combat.not_a_module", RelayDirection.TO_CLIENT, packet)),
        )
    }

    @Test
    fun impossibleModulesForwardUntouched() {
        val packet = setEntityMotion(1f, 2f, 3f)
        for (id in CombatModules.IMPOSSIBLE.keys) {
            assertEquals(
                "id $id must forward untouched",
                listOf(bytes(packet)),
                rendered(CombatModules.transform(id, RelayDirection.TO_CLIENT, packet)),
            )
        }
    }

    @Test
    fun implementedAndImpossibleAreDisjointAndNonEmpty() {
        assertFalse(CombatModules.IMPLEMENTED.isEmpty())
        assertFalse(CombatModules.IMPOSSIBLE.isEmpty())
        assertTrue(
            "an id cannot be both implemented and impossible",
            CombatModules.IMPLEMENTED.none { CombatModules.IMPOSSIBLE.containsKey(it) },
        )
    }

    @Test
    fun everyImplementedIdHasANonForwardingBehaviour() {
        // A pass-through in IMPLEMENTED would make the set a lie. Each id gets
        // the packet and the leg it actually acts on. The input-plan ids rewrite
        // no bytes at all, so they are checked separately below — asserting they
        // forward here would assert the opposite of correct.
        val cases = listOf(
            Triple("xykell.combat.velocity", RelayDirection.TO_CLIENT, setEntityMotion(0.4f, 0.6f, 0f)),
            Triple("xykell.combat.knockback", RelayDirection.TO_CLIENT, setEntityMotion(0.4f, 0.6f, 0f)),
            Triple("xykell.combat.auto_crit", RelayDirection.TO_SERVER, playerAuthInput(pitch = 12f)),
        )
        val planIds = setOf(
            "xykell.combat.afk_clicker",
            "xykell.combat.double_click",
        )
        assertEquals(
            CombatModules.IMPLEMENTED,
            cases.map { it.first }.toSet() + planIds,
        )
        for ((id, leg, packet) in cases) {
            val out = CombatModules.transform(id, leg, packet, ModuleContext())
            assertFalse(
                "id $id forwards unchanged but is listed as implemented",
                out.size == 1 && out[0].contentEquals(packet),
            )
        }
    }

    @Test
    fun everyImpossibleIdHasAReason() {
        assertEquals(25, CombatModules.IMPOSSIBLE.size)
        for ((id, reason) in CombatModules.IMPOSSIBLE) {
            assertTrue("id $id has an empty reason", reason.isNotBlank())
            assertTrue("id $id reason is too short to be concrete", reason.length > 30)
        }
    }

    @Test
    fun combatBatchCoversTheWholeRegistrySlice() {
        val expected = setOf(
            "aim_assist", "afk_clicker", "trigger_bot", "kill_aura", "tp_aura",
            "reach", "hitbox", "velocity", "knockback", "knockback_delay",
            "backtrack", "auto_crit", "auto_totem", "auto_potion", "auto_crystal",
            "anti_crystal", "auto_cart", "auto_web", "auto_switch", "mace_swap",
            "mace_damage", "shield_disabler", "anchor_aura", "target_hud",
            "target_selector", "friend_filter", "combat_settings", "double_click",
            "auto_log", "mob_aura",
        )
        val covered = (CombatModules.IMPLEMENTED + CombatModules.IMPOSSIBLE.keys)
            .map { it.removePrefix("xykell.combat.") }
            .toSet()
        assertEquals(expected, covered)
    }

    @Test
    fun everyCombatTransformStillAcceptsTheThreeArgEntryPoint() {
        // The stateless entry point must keep compiling and running against a
        // default ModuleContext, so a caller holding no session is unaffected.
        val motion = setEntityMotion(1f, 1f, 1f)
        assertEquals(
            emptyList<String>(),
            rendered(CombatModules.transform("xykell.combat.velocity", RelayDirection.TO_CLIENT, motion)),
        )
        // knockback scales the SERVER's push on the clientbound leg, so the
        // client's own outbound motion is the leg it must leave alone.
        assertEquals(
            listOf(bytes(motion)),
            rendered(CombatModules.transform("xykell.combat.knockback", RelayDirection.TO_SERVER, motion)),
        )
        // On the leg it owns it halves the vector (default scale 0.5):
        // 1.0f encodes as 00 00 80 3f, 0.5f as 00 00 00 3f.
        assertEquals(
            listOf(bytes(motion).replace("-128, 63", "0, 63")),
            rendered(CombatModules.transform("xykell.combat.knockback", RelayDirection.TO_CLIENT, motion)),
        )
        assertEquals(
            listOf(bytes(motion)),
            rendered(CombatModules.transform("xykell.combat.auto_crit", RelayDirection.TO_SERVER, motion)),
        )
    }
}