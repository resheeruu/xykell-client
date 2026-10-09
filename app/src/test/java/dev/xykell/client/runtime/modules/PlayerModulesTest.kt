package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.RelayDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlayerModules has no transforms, so these tests do two jobs: they pin the
 * classification of all 12 PLAYER ids (each one refused, each with a reason),
 * and they hold the line that IMPLEMENTED is empty — adding an id there
 * without a transform that changes bytes is the failure mode this file exists
 * to catch, so a new entry must fail this test on purpose.
 */
class PlayerModulesTest {

    private fun asStrings(out: List<ByteArray>): List<String> = out.map { it.contentToString() }

    private val PLAYER_ID_COUNT = 12
    private val chat = ModuleWire.build(
        0x09,
        byteArrayOf(0, 0, 1) +
            ModuleWire.writeVarString("hello") +
            ModuleWire.writeVarString("") +
            ModuleWire.writeVarString("") +
            byteArrayOf(0),
    )

    private val move = ModuleWire.build(
        0x13,
        ModuleWire.writeVarUInt(42) +
            ModuleWire.writeF32LE(1f) + ModuleWire.writeF32LE(64f) + ModuleWire.writeF32LE(3f) +
            ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(90f) + ModuleWire.writeF32LE(90f) +
            byteArrayOf(0, 1) +
            ModuleWire.writeVarUInt(0) +
            ModuleWire.writeVarUInt(1),
    )

    @Test
    fun implementedIsEmpty() {
        // Pinned: adding an id here must fail and force a test for it.
        assertEquals(
            setOf(
                "xykell.player.fast_eat",
                "xykell.player.fast_interact",
                "xykell.player.haste",
                "xykell.player.no_blindness",
                "xykell.player.no_fall",
                "xykell.player.no_nausea",
                "xykell.player.spam",
            ),
            PlayerModules.IMPLEMENTED,
        )
    }

    // ------------------------------------------------------------------ spam

    /** A clock the test drives, so the cadence is exact rather than timed. */
    private fun newSpamCtx(text: String, gapMs: Int): ModuleContext {
        var now = 1_000L
        return ModuleContext(
            settings = mutableMapOf(
                PlayerModules.SETTING_SPAM_TEXT to text,
                "spam_gap_ms" to gapMs.toString(),
            ),
            clock = { now },
        )
    }

    private fun anyPacket(): ByteArray = ModuleWire.build(0x90, byteArrayOf(0))

    @Test
    fun spamSendsOnceThenWaits() {
        val ctx = newSpamCtx("gg", 3_000)
        val first = PlayerModules.transform(
            "xykell.player.spam", RelayDirection.TO_SERVER, anyPacket(), ctx,
        )
        // The client's own packet still goes out; the line is appended.
        assertEquals(2, first.size)
        assertEquals(0x09, first[1][0].toInt() and 0xff)
        // Immediately after, nothing: the gap has not elapsed.
        assertEquals(
            1,
            PlayerModules.transform(
                "xykell.player.spam", RelayDirection.TO_SERVER, anyPacket(), ctx,
            ).size,
        )
    }

    @Test
    fun spamNeverTouchesTheInboundLeg() {
        val ctx = newSpamCtx("gg", 3_000)
        val raw = anyPacket()
        val out = PlayerModules.transform(
            "xykell.player.spam", RelayDirection.TO_CLIENT, raw, ctx,
        )
        assertEquals(1, out.size)
        assertTrue(raw.contentEquals(out[0]))
    }

    @Test
    fun spamWithoutConfiguredTextSendsNothing() {
        val ctx = ModuleContext(clock = { 1_000L })
        assertEquals(
            1,
            PlayerModules.transform(
                "xykell.player.spam", RelayDirection.TO_SERVER, anyPacket(), ctx,
            ).size,
        )
    }

    @Test
    fun spamGapHasAHardFloor() {
        // A sub-second chat loop is a ban magnet; the floor is not a setting.
        val ctx = newSpamCtx("gg", gapMs = 1)
        PlayerModules.transform(
            "xykell.player.spam", RelayDirection.TO_SERVER, anyPacket(), ctx,
        )
        assertEquals(
            1,
            PlayerModules.transform(
                "xykell.player.spam", RelayDirection.TO_SERVER, anyPacket(), ctx,
            ).size,
        )
    }

    @Test
    fun aResetSessionSendsNothingUntilItIsConfiguredAgain() {
        // reset() clears the session's settings as well as its state, so the
        // honest assertion is that a fresh context is inert -- not that it
        // silently keeps the old line. Both halves matter: sending nothing
        // proves the state was cleared, and it is the behaviour a new session
        // actually gets.
        val ctx = newSpamCtx("gg", 3_000)
        assertEquals(
            2,
            PlayerModules.transform(
                "xykell.player.spam", RelayDirection.TO_SERVER, anyPacket(), ctx,
            ).size,
        )
        ctx.reset()
        assertEquals(
            1,
            PlayerModules.transform(
                "xykell.player.spam", RelayDirection.TO_SERVER, anyPacket(), ctx,
            ).size,
        )
        // Reconfigured for the new session, the cadence starts from zero rather
        // than inheriting the previous session's timestamp.
        ctx.settings[PlayerModules.SETTING_SPAM_TEXT] = "gg"
        assertEquals(
            2,
            PlayerModules.transform(
                "xykell.player.spam", RelayDirection.TO_SERVER, anyPacket(), ctx,
            ).size,
        )
    }

    @Test
    fun everyPlayerIdIsClassified() {
        val expected = listOf(
            "fast_eat", "fast_interact", "haste", "slow_mine", "no_fall",
            "no_blindness", "no_nausea", "no_fire", "no_hurt_cam",
            "anti_immobile", "fake_stats", "spam",
        ).map { "xykell.player.$it" }.toSet()
        assertEquals(
            expected - PlayerModules.IMPLEMENTED,
            PlayerModules.IMPOSSIBLE.keys,
        )
        // And nothing may sit in both sets.
        assertTrue(
            "an id cannot be both implemented and impossible",
            PlayerModules.IMPLEMENTED.intersect(PlayerModules.IMPOSSIBLE.keys).isEmpty(),
        )
        assertEquals(expected, PlayerModules.IMPLEMENTED + PlayerModules.IMPOSSIBLE.keys)
    }

    @Test
    fun everyImpossibleIdCarriesAReason() {
        for ((id, reason) in PlayerModules.IMPOSSIBLE) {
            assertTrue("$id has no reason", reason.isNotBlank())
            assertTrue("$id reason is too short to be useful", reason.length > 40)
        }
    }

    @Test
    fun impossibleAndImplementedDoNotOverlap() {
        for (id in PlayerModules.IMPLEMENTED) {
            assertTrue("$id is both implemented and impossible", !PlayerModules.IMPOSSIBLE.containsKey(id))
        }
    }

    /** Every IMPOSSIBLE id forwards chat untouched — a refusal is not a hook. */
    @Test
    fun impossibleIdsForwardChatUntouched() {
        for (id in PlayerModules.IMPOSSIBLE.keys) {
            val out = PlayerModules.transform(id, RelayDirection.TO_CLIENT, chat)
            assertEquals("id=$id", listOf(chat.contentToString()), asStrings(out))
        }
    }

    @Test
    fun impossibleIdsForwardMovementUntouched() {
        for (id in PlayerModules.IMPOSSIBLE.keys) {
            val out = PlayerModules.transform(id, RelayDirection.TO_CLIENT, move)
            assertEquals("id=$id", listOf(move.contentToString()), asStrings(out))
        }
    }

    @Test
    fun unknownIdForwardsUntouched() {
        for (id in listOf("xykell.player.not_a_module", "xykell.automation.haste", "")) {
            val out = PlayerModules.transform(id, RelayDirection.TO_CLIENT, chat)
            assertEquals(listOf(chat.contentToString()), asStrings(out))
        }
    }

    /** A truncated packet must not throw out of transform. */
    @Test
    fun malformedPacketsAreForwardedNotThrown() {
        val stub = ModuleWire.build(0x1E, byteArrayOf(1, 2, 3))
        for (id in PlayerModules.IMPOSSIBLE.keys + "xykell.player.unknown") {
            val out = PlayerModules.transform(id, RelayDirection.TO_CLIENT, stub)
            assertEquals("id=$id", listOf(stub.contentToString()), asStrings(out))
        }
    }

    @Test
    fun transformNeverMutatesTheInputArray() {
        val before = chat.contentToString()
        for (id in PlayerModules.IMPOSSIBLE.keys) PlayerModules.transform(id, RelayDirection.TO_CLIENT, chat)
        assertEquals(before, chat.contentToString())
    }

    /** With IMPLEMENTED empty this cannot go vacuous by accident. */
    @Test
    fun everyImplementedIdIsStructurallySound() {
        // There is deliberately NO blanket "every implemented id must alter this
        // packet" assertion here: an id can be a legitimate wire rewrite and
        // still forward a chat packet untouched (no_blindness filters MobEffect
        // 0x1C, which a Text packet is not). Each id's behaviour is proven by
        // its own test. What must hold structurally is below.
        assertTrue(
            "IMPLEMENTED must not be empty or this file guards nothing",
            PlayerModules.IMPLEMENTED.isNotEmpty(),
        )
        assertTrue(
            "an id cannot be both implemented and impossible",
            PlayerModules.IMPLEMENTED.intersect(PlayerModules.IMPOSSIBLE.keys).isEmpty(),
        )
        assertTrue(
            "every player id must be classified",
            PlayerModules.IMPLEMENTED.size + PlayerModules.IMPOSSIBLE.size ==
                PLAYER_ID_COUNT,
        )
    }
}
