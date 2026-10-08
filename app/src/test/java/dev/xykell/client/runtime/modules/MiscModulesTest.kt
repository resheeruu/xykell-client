package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.RelayDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscModulesTest {

    private fun setHealth(health: Int): ByteArray =
        ModuleWire.build(MiscModules.ID_SET_HEALTH, ModuleWire.writeVarInt(health))

    private fun setTime(ticks: Int): ByteArray =
        ModuleWire.build(0x0A, ModuleWire.writeVarInt(ticks))

    private fun shapes(actual: List<ByteArray>): List<String> = actual.map { it.contentToString() }

    @Test
    fun disablerDropsSetHealth() {
        assertTrue(MiscModules.transform("xykell.misc.disabler", RelayDirection.TO_CLIENT, setHealth(18)).isEmpty())
    }

    @Test
    fun disablerDropsSetHealthRegardlessOfValue() {
        assertTrue(MiscModules.transform("xykell.misc.disabler", RelayDirection.TO_CLIENT, setHealth(0)).isEmpty())
        assertTrue(MiscModules.transform("xykell.misc.disabler", RelayDirection.TO_CLIENT, setHealth(20)).isEmpty())
    }

    @Test
    fun disablerForwardsSetTimeUntouched() {
        val pkt = setTime(6000)
        assertEquals(listOf(pkt.contentToString()), shapes(MiscModules.transform("xykell.misc.disabler", RelayDirection.TO_CLIENT, pkt)))
    }

    @Test
    fun disablerForwardsUnknownIdUntouched() {
        val pkt = ModuleWire.build(0x77, ModuleWire.writeVarUInt(9))
        assertEquals(listOf(pkt.contentToString()), shapes(MiscModules.transform("xykell.misc.disabler", RelayDirection.TO_CLIENT, pkt)))
    }

    @Test
    fun disablerForwardsTruncatedPacket() {
        val pkt = byteArrayOf(0x8A.toByte())
        assertEquals(listOf(pkt.contentToString()), shapes(MiscModules.transform("xykell.misc.disabler", RelayDirection.TO_CLIENT, pkt)))
    }

    @Test
    fun disablerForwardsEmptyPacket() {
        val out = MiscModules.transform("xykell.misc.disabler", RelayDirection.TO_CLIENT, ByteArray(0))
        assertEquals(1, out.size)
        assertEquals(0, out[0].size)
    }

    @Test
    fun disablerMatchesIdThroughSubClientBits() {
        val pkt = ModuleWire.build(MiscModules.ID_SET_HEALTH or 0x400, ModuleWire.writeVarInt(18))
        assertTrue(MiscModules.transform("xykell.misc.disabler", RelayDirection.TO_CLIENT, pkt).isEmpty())
    }

    @Test
    fun unknownIdForwardsUntouched() {
        val pkt = setHealth(18)
        assertEquals(listOf(pkt.contentToString()), shapes(MiscModules.transform("xykell.misc.not_a_module", RelayDirection.TO_CLIENT, pkt)))
    }

    @Test
    fun everyImplementedIdHasBehaviour() {
        assertEquals(
            setOf(
                "xykell.misc.anti_weather",
                "xykell.misc.disabler",
                "xykell.misc.toggle_sneak",
                "xykell.misc.toggle_sprint",
            ),
            MiscModules.IMPLEMENTED,
        )
    }

    @Test
    fun impossibleCoversTheOtherNineWithReasons() {
        assertEquals(
            setOf(
                "xykell.misc.quick_perspective",
                "xykell.misc.quick_drop",
                "xykell.misc.toggle_sprint",
                "xykell.misc.toggle_sneak",
                "xykell.misc.fake_op",
                "xykell.misc.java_mode",
                "xykell.misc.skin_stealer",
                "xykell.misc.anti_weather",
                "xykell.misc.fast_throw",
            ).minus(MiscModules.IMPLEMENTED),
            MiscModules.IMPOSSIBLE.keys,
        )
        // Nothing may sit in both sets, and the two must partition MISC.
        assertTrue(
            MiscModules.IMPLEMENTED.intersect(MiscModules.IMPOSSIBLE.keys).isEmpty(),
        )
        for (reason in MiscModules.IMPOSSIBLE.values) {
            assertTrue(reason.isNotBlank())
        }
    }

    @Test
    fun implementedAndImpossibleDoNotOverlap() {
        for (id in MiscModules.IMPLEMENTED) {
            assertTrue(!MiscModules.IMPOSSIBLE.containsKey(id))
        }
    }

    @Test
    fun tenMiscIdsAccountedFor() {
        assertEquals(10, MiscModules.IMPLEMENTED.size + MiscModules.IMPOSSIBLE.size)
    }

    @Test
    fun impossibleIdsForwardUntouched() {
        val pkt = setHealth(18)
        for (id in MiscModules.IMPOSSIBLE.keys) {
            assertEquals(id, listOf(pkt.contentToString()), shapes(MiscModules.transform(id, RelayDirection.TO_CLIENT, pkt)))
        }
    }

    @Test
    fun `disabler only touches the serverbound leg`() {
        val health = setHealth(7)
        val out = MiscModules.transform(
            "xykell.misc.disabler", RelayDirection.TO_SERVER, health,
        )
        assertEquals(listOf(health.contentToString()), out.map { it.contentToString() })
    }
}