package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.RelayDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModulesTest {

    private fun setTime(ticks: Int): ByteArray =
        ModuleWire.build(0x0A, ModuleWire.writeVarInt(ticks))

    private fun setHealth(health: Int): ByteArray =
        ModuleWire.build(0x2A, ModuleWire.writeVarInt(health))

    private fun text(message: String): ByteArray =
        ModuleWire.build(
            0x09,
            byteArrayOf(0, 0, 1),
            ModuleWire.writeVarString(message),
            ModuleWire.writeVarString(""),
            ModuleWire.writeVarString(""),
        )

    private fun movePlayer(): ByteArray =
        ModuleWire.build(
            0x13,
            byteArrayOf(0x01),
            ModuleWire.writeF32LE(10f),
            ModuleWire.writeF32LE(64f),
            ModuleWire.writeF32LE(-3f),
            ModuleWire.writeF32LE(0f),
            ModuleWire.writeF32LE(90f),
            ModuleWire.writeF32LE(90f),
            byteArrayOf(0, 1),
        )

    @Test
    fun monitorReportsClock() {
        val o = NetworkModules.monitor(setTime(6000))
        assertTrue(o is NetworkModules.Observation.Clock)
        assertEquals(6000, (o as NetworkModules.Observation.Clock).timeTicks)
    }

    @Test
    fun monitorReportsNegativeClock() {
        val o = NetworkModules.monitor(setTime(-1))
        assertEquals(-1, (o as NetworkModules.Observation.Clock).timeTicks)
    }

    @Test
    fun monitorReportsHealth() {
        val o = NetworkModules.monitor(setHealth(17))
        assertEquals(17, (o as NetworkModules.Observation.Health).health)
    }

    @Test
    fun monitorIdentifiesTextWithoutClaimingLayout() {
        val pkt = text("hello")
        val o = NetworkModules.monitor(pkt)
        assertTrue(o is NetworkModules.Observation.Identified)
        o as NetworkModules.Observation.Identified
        assertEquals(0x09, o.id)
        assertEquals(pkt.size - ModuleWire.bodyStart(pkt)!!, o.bodyLength)
    }

    @Test
    fun monitorIdentifiesMovePlayerWithoutClaimingLayout() {
        val pkt = movePlayer()
        val o = NetworkModules.monitor(pkt) as NetworkModules.Observation.Identified
        assertEquals(0x13, o.id)
        assertEquals(pkt.size - ModuleWire.bodyStart(pkt)!!, o.bodyLength)
    }

    @Test
    fun monitorReturnsNullOnTruncatedHeader() {
        assertNull(NetworkModules.monitor(byteArrayOf(0x8A.toByte())))
    }

    @Test
    fun monitorReturnsNullOnEmptyPacket() {
        assertNull(NetworkModules.monitor(ByteArray(0)))
    }

    @Test
    fun monitorFallsBackToIdentifiedWhenSetTimeBodyTruncated() {
        val pkt = ModuleWire.build(0x0A, byteArrayOf(0x80.toByte()))
        val o = NetworkModules.monitor(pkt)
        assertTrue(o is NetworkModules.Observation.Identified)
        assertEquals(0x0A, (o as NetworkModules.Observation.Identified).id)
    }

    @Test
    fun loggerFormatsClock() {
        assertEquals("set_time ticks=6000", NetworkModules.logger(setTime(6000)))
    }

    @Test
    fun loggerFormatsHealth() {
        assertEquals("set_health health=17", NetworkModules.logger(setHealth(17)))
    }

    @Test
    fun loggerFormatsIdentified() {
        // Text body here is 3 tag bytes + 6 message bytes + 2 empty strings.
        assertEquals("packet id=0x9 bytes=11", NetworkModules.logger(text("hello")))
    }

    @Test
    fun loggerReturnsNullOnUnidentifiablePacket() {
        assertNull(NetworkModules.logger(ByteArray(0)))
    }

    @Test
    fun transformNeverRewritesForEitherReader() {
        val pkt = setHealth(17)
        for (id in NetworkModules.READERS) {
            val out = NetworkModules.transform(id, RelayDirection.TO_CLIENT, pkt)
            assertEquals(1, out.size)
            assertEquals(pkt.contentToString(), out[0].contentToString())
        }
    }

    @Test
    fun transformForwardsEveryIdUntouched() {
        val pkt = text("hello")
        assertEquals(listOf(pkt.contentToString()), NetworkModules.transform("anything", RelayDirection.TO_CLIENT, pkt).map { it.contentToString() })
    }

    @Test
    fun noNetworkIdIsClaimedAsATransform() {
        assertTrue(NetworkModules.IMPLEMENTED.isEmpty())
        assertTrue(NetworkModules.IMPOSSIBLE.isEmpty())
    }

    @Test
    fun bothNetworkIdsAreDeliveredAsReaders() {
        assertEquals(
            setOf("xykell.network.packet_monitor", "xykell.network.packet_logger"),
            NetworkModules.READERS,
        )
    }

    @Test
    fun readersDoNotAlterThePacket() {
        val pkt = setHealth(17)
        val before = pkt.contentToString()
        NetworkModules.monitor(pkt)
        NetworkModules.logger(pkt)
        assertEquals(before, pkt.contentToString())
    }
}