package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RakNetCodecTest {

    @Test
    fun `ping round trip preserves time`() {
        val pkt = OfflinePacket.Ping(1234567890123L)
        val decoded = RakNetOffline.decode(RakNetOffline.encode(pkt)) as OfflinePacket.Ping
        assertEquals(1234567890123L, decoded.time)
    }

    @Test
    fun `ping tolerates trailing session bytes`() {
        val wire = RakNetOffline.encode(OfflinePacket.Ping(7L)) + byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val decoded = RakNetOffline.decode(wire) as OfflinePacket.Ping
        assertEquals(7L, decoded.time)
    }

    @Test
    fun `pong round trip preserves guid and motd`() {
        val motd = "MCPE;1.21.51;Xykell;19132;0;10;123;Bedrock level;Survival;1;19132;19133;"
        val pkt = OfflinePacket.Pong(42L, -123456789L, motd)
        val decoded = RakNetOffline.decode(RakNetOffline.encode(pkt)) as OfflinePacket.Pong
        assertEquals(42L, decoded.time)
        assertEquals(-123456789L, decoded.guid)
        assertEquals(motd, decoded.motd)
    }

    @Test
    fun `pong motd round trips non-ascii utf8`() {
        val motd = "服务器;测试"
        val decoded = RakNetOffline.decode(RakNetOffline.encode(OfflinePacket.Pong(1L, 2L, motd))) as OfflinePacket.Pong
        assertEquals(motd, decoded.motd)
    }

    @Test
    fun `pong rejects trailing garbage`() {
        val wire = RakNetOffline.encode(OfflinePacket.Pong(1L, 2L, "x")) + 0x7f
        try {
            RakNetOffline.decode(wire)
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("trailing"))
        }
    }

    @Test
    fun `request1 pads to declared mtu minus ip overhead and round trips`() {
        val wire = RakNetOffline.encode(OfflinePacket.OpenRequest1(11, 1400))
        assertEquals(1400 - RakNetOffline.IP_UDP_OVERHEAD, wire.size)
        val decoded = RakNetOffline.decode(wire) as OfflinePacket.OpenRequest1
        assertEquals(11, decoded.protocolVersion)
        assertEquals(1400, decoded.mtu)
    }

    @Test
    fun `request1 rejects out of range mtu`() {
        for (bad in intArrayOf(RakNetOffline.MIN_MTU - 1, RakNetOffline.MAX_MTU + 1)) {
            try {
                RakNetOffline.encode(OfflinePacket.OpenRequest1(11, bad))
                throw AssertionError("expected format exception for mtu $bad")
            } catch (expected: RakNetFormatException) {
                assertTrue(expected.message!!.contains("mtu"))
            }
        }
    }

    @Test
    fun `reply1 round trips guid security cookie and mtu`() {
        val pkt = OfflinePacket.OpenReply1(9999L, security = true, mtu = 1492, cookie = 0x01020304)
        val decoded = RakNetOffline.decode(RakNetOffline.encode(pkt)) as OfflinePacket.OpenReply1
        assertEquals(9999L, decoded.guid)
        assertTrue(decoded.security)
        assertEquals(0x01020304, decoded.cookie)
        assertEquals(1492, decoded.mtu)
    }

    @Test
    fun `request2 round trips ipv4 address`() {
        val pkt = OfflinePacket.OpenRequest2(RakNetAddress("127.0.0.1", 19132), 1400, 77L)
        val decoded = RakNetOffline.decode(RakNetOffline.encode(pkt)) as OfflinePacket.OpenRequest2
        assertEquals("127.0.0.1", decoded.server.host)
        assertEquals(19132, decoded.server.port)
        assertEquals(1400, decoded.mtu)
        assertEquals(77L, decoded.guid)
    }

    @Test
    fun `reply1 id is 0x06 and request2 id is 0x07`() {
        val reply1 = RakNetOffline.encode(OfflinePacket.OpenReply1(1L, security = false, mtu = 576))
        assertEquals(0x06, reply1[0].toInt() and 0xff)
        val req2 = RakNetOffline.encode(OfflinePacket.OpenRequest2(RakNetAddress("1.2.3.4", 1), 576, 0L))
        assertEquals(0x07, req2[0].toInt() and 0xff)
    }

    @Test
    fun `request2 writes cookie and challenge after magic when present`() {
        val wire = RakNetOffline.encode(
            OfflinePacket.OpenRequest2(RakNetAddress("1.2.3.4", 1), 576, 0L, cookie = 0x0a0b0c0d)
        )
        // [07][magic16][cookie BE 4][challenge 0][addr...]
        val off = 1 + 16
        assertTrue(
            wire.copyOfRange(off, off + 5)
                .contentEquals(byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d, 0))
        )
    }

    @Test
    fun `address writes ipv4 octets complemented on wire`() {
        val wire = RakNetOffline.encode(OfflinePacket.OpenRequest2(RakNetAddress("1.2.3.4", 1), 576, 0L))
        // [id][magic16] then address: 04 ~(1)~(2)~(3)~(4) port(00 01)
        val addr = wire.copyOfRange(1 + 16, 1 + 16 + 7)
        assertTrue(addr.contentEquals(byteArrayOf(4, 0xfe.toByte(), 0xfd.toByte(), 0xfc.toByte(), 0xfb.toByte(), 0, 1)))
    }

    @Test
    fun `reply2 round trips encryption flag`() {
        val pkt = OfflinePacket.OpenReply2(5L, RakNetAddress("10.0.0.7", 40000), 1200, encryption = true)
        val decoded = RakNetOffline.decode(RakNetOffline.encode(pkt)) as OfflinePacket.OpenReply2
        assertEquals("10.0.0.7", decoded.client.host)
        assertEquals(40000, decoded.client.port)
        assertTrue(decoded.encryption)
    }

    @Test
    fun `rejects empty and truncated packets`() {
        for (bad in listOf(byteArrayOf(), byteArrayOf(0x1c), byteArrayOf(0x1c, 0, 0, 0, 0, 0, 0, 0))) {
            try {
                RakNetOffline.decode(bad)
                throw AssertionError("expected format exception")
            } catch (expected: RakNetFormatException) {
                // underflow or empty — both fine
            }
        }
    }

    @Test
    fun `rejects unknown packet id`() {
        try {
            RakNetOffline.decode(byteArrayOf(0x99.toByte()))
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("unknown"))
        }
    }

    @Test
    fun `rejects wrong magic in reply1`() {
        val wire = RakNetOffline.encode(OfflinePacket.OpenReply1(1L, false, 576))
        wire[2] = 0x12 // corrupt a magic byte (magic starts at offset 1)
        try {
            RakNetOffline.decode(wire)
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("magic"))
        }
    }

    @Test
    fun `rejects ipv6 address version byte`() {
        val w = RakNetWriter()
        w.u8(0x07) // OpenRequest2
        w.bytes(RakNetOffline.MAGIC)
        w.u8(6) // version 6 not implemented
        try {
            RakNetOffline.decode(w.toByteArray())
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("version"))
        }
    }

    @Test
    fun `encode rejects bad host and port`() {
        for (bad in listOf(RakNetAddress("999.1.1.1", 1), RakNetAddress("1.2.3", 1), RakNetAddress("a.b.c.d", 1))) {
            try {
                RakNetOffline.encode(OfflinePacket.OpenRequest2(bad, 576, 0L))
                throw AssertionError("expected format exception for $bad")
            } catch (expected: RakNetFormatException) {
                // rejected
            }
        }
        try {
            RakNetOffline.encode(OfflinePacket.OpenRequest2(RakNetAddress("1.2.3.4", 70000), 576, 0L))
            throw AssertionError("expected format exception for bad port")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("port"))
        }
    }
}
