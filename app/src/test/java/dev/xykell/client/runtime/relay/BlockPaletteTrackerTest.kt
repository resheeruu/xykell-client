package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracker is the caller that was missing: before it existed the decoder was
 * tested but unreachable, so a palette could never reach a module.
 */
class BlockPaletteTrackerTest {

    private val startGameProtocol = StartGamePalettePlan.PLANS.keys.first()
    private val protocolNumber = StartGamePalettePlan.PROTOCOL_VERSIONS.entries
        .first { it.value == startGameProtocol }.key

    private fun startGamePacket(entries: List<Pair<String, ByteArray>>): ByteArray {
        return StartGameEncoder.startGameWith(startGameProtocol, entries)
    }

    @Test
    fun `a real StartGame produces a usable palette`() {
        val tracker = BlockPaletteTracker()
        val packet = startGamePacket(
            listOf(
                "minecraft:stone" to StartGameEncoder.nbt("version" to 1),
                "minecraft:oak_log" to StartGameEncoder.nbt("version" to 2),
            ),
        )
        assertTrue(tracker.offer(protocolNumber, packet))
        val state = tracker.state
        assertTrue("state was $state", state is BlockPaletteTracker.State.Available)
        val available = state as BlockPaletteTracker.State.Available
        assertEquals(startGameProtocol, available.version)
        assertEquals(2, available.palette.size)
        assertNotNull(tracker.palette)
    }

    @Test
    fun `unrelated packets are ignored`() {
        val tracker = BlockPaletteTracker()
        // Not a StartGame: the tracker must not claim it, and must not move
        // state either.
        assertTrue(!tracker.offer(protocolNumber, byteArrayOf(0x09, 0x00, 0x00)))
        assertEquals(BlockPaletteTracker.State.AwaitingStartGame, tracker.state)
        assertNull(tracker.palette)
    }

    @Test
    fun `a modern protocol is refused with a reason, not silently empty`() {
        val tracker = BlockPaletteTracker()
        val modern = StartGamePalettePlan.PROTOCOL_VERSIONS.entries
            .first { it.value == "1.26.45" }.key
        // Use the 1.26.45 StartGame id so the packet is recognisably a
        // StartGame even though that protocol has no palette.
        val id = BedrockPacketIds.idForVersion("1.26.45", "StartGame")!!
        assertTrue(tracker.offer(modern, byteArrayOf(id.toByte(), 0x00, 0x00)))
        val state = tracker.state
        assertTrue("state was $state", state is BlockPaletteTracker.State.Refused)
        val refused = state as BlockPaletteTracker.State.Refused
        assertEquals(StartGamePaletteDecoder.Refusal.NO_PALETTE_IN_HANDSHAKE, refused.reason)
        assertTrue(refused.detail.isNotBlank())
        assertNull(tracker.palette)
    }

    @Test
    fun `an unrecognised protocol number is unknown, not the default`() {
        val tracker = BlockPaletteTracker()
        val id = BedrockPacketIds.infoOf("StartGame")!!.id
        assertTrue(tracker.offer(999999, byteArrayOf(id.toByte(), 0x00, 0x00)))
        val state = tracker.state
        assertTrue(state is BlockPaletteTracker.State.Refused)
        assertEquals(
            StartGamePaletteDecoder.Refusal.UNKNOWN_VERSION,
            (state as BlockPaletteTracker.State.Refused).reason,
        )
    }

    @Test
    fun `a truncated handshake refuses rather than half-decoding`() {
        val tracker = BlockPaletteTracker()
        val full = startGamePacket(
            listOf("minecraft:stone" to StartGameEncoder.nbt("version" to 1)),
        )
        tracker.offer(protocolNumber, full.copyOf(full.size / 2))
        assertTrue(tracker.state is BlockPaletteTracker.State.Refused)
        assertNull(tracker.palette)
    }

    @Test
    fun `protocol numbers resolve to the schemas that can decode them`() {
        // The handshake carries a number, so this mapping is the only thing
        // standing between a live session and a decoder.
        for ((number, version) in StartGamePalettePlan.PROTOCOL_VERSIONS) {
            assertEquals(version, StartGamePalettePlan.versionFor(number))
        }
        assertNull(StartGamePalettePlan.versionFor(-1))
    }
}