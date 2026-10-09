package dev.xykell.client.runtime.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VPN transport codec. Every case here is a silent-corruption risk: a bad
 * offset does not throw, it injects garbage into the player's game session.
 */
class VpnPacketTest {

    private val srcIp = byteArrayOf(10, 0, 2, 15)
    private val dstIp = byteArrayOf(203.toByte(), 0, 113.toByte(), 7)

    private fun udpPacket(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        id: Int = 0x1234,
    ): ByteArray = VpnPacket.buildIpv4Udp(
        VpnPacket.Datagram(src, srcPort, dst, dstPort, payload), id,
    )

    @Test
    fun `round trip preserves every field`() {
        val payload = byteArrayOf(0xFE.toByte(), 0xFD.toByte(), 0x01, 0x02)
        val parsed = VpnPacket.parseIpv4Udp(
            udpPacket(srcIp, dstIp, 51000, VpnPacket.BEDROCK_PORT, payload),
        )
        assertNotNull(parsed)
        assertArrayEquals(srcIp, parsed!!.sourceAddress)
        assertArrayEquals(dstIp, parsed.destAddress)
        assertEquals(51000, parsed.sourcePort)
        assertEquals(VpnPacket.BEDROCK_PORT, parsed.destPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `round trip survives an empty payload`() {
        val parsed = VpnPacket.parseIpv4Udp(
            udpPacket(srcIp, dstIp, 51000, VpnPacket.BEDROCK_PORT, ByteArray(0)),
        )
        assertNotNull(parsed)
        assertEquals(0, parsed!!.payload.size)
    }

    @Test
    fun `payload with high bytes is not sign extended`() {
        // Regression: a naive ByteArray copy that round trips through Int would
        // corrupt every byte above 0x7F, and Bedrock payloads are mostly those.
        val payload = ByteArray(256) { it.toByte() }
        val parsed = VpnPacket.parseIpv4Udp(
            udpPacket(srcIp, dstIp, 1, VpnPacket.BEDROCK_PORT, payload),
        )
        assertArrayEquals(payload, parsed!!.payload)
    }

    @Test
    fun `built header carries a valid ipv4 checksum`() {
        val packet = udpPacket(srcIp, dstIp, 51000, VpnPacket.BEDROCK_PORT, byteArrayOf(1))
        // Summing the header including its own checksum must yield zero.
        assertEquals(0, VpnPacket.checksum(packet, 0, 20))
    }

    @Test
    fun `bedrock port is detected in both directions`() {
        val toServer = VpnPacket.parseIpv4Udp(
            udpPacket(srcIp, dstIp, 51000, VpnPacket.BEDROCK_PORT, byteArrayOf(1)),
        )
        assertTrue(toServer!!.isBedrock())

        val fromServer = VpnPacket.parseIpv4Udp(
            udpPacket(dstIp, srcIp, VpnPacket.BEDROCK_PORT, 51000, byteArrayOf(1)),
        )
        assertTrue(fromServer!!.isBedrock())

        val other = VpnPacket.parseIpv4Udp(
            udpPacket(srcIp, dstIp, 51000, 443, byteArrayOf(1)),
        )
        assertTrue(!other!!.isBedrock())
    }

    @Test
    fun `reply is the mirror image of the request`() {
        val request = VpnPacket.parseIpv4Udp(
            udpPacket(srcIp, dstIp, 51000, VpnPacket.BEDROCK_PORT, byteArrayOf(9, 9)),
        )!!
        // Server answers: addresses and ports swapped, payload different.
        val reply = VpnPacket.Datagram(
            request.destAddress, request.destPort,
            request.sourceAddress, request.sourcePort,
            byteArrayOf(7),
        )
        val parsedReply = VpnPacket.parseIpv4Udp(VpnPacket.buildIpv4Udp(reply, 0x4321))!!
        assertArrayEquals(dstIp, parsedReply.sourceAddress)
        assertArrayEquals(srcIp, parsedReply.destAddress)
        assertEquals(VpnPacket.BEDROCK_PORT, parsedReply.sourcePort)
        assertEquals(51000, parsedReply.destPort)
        assertArrayEquals(byteArrayOf(7), parsedReply.payload)
    }

    @Test
    fun `rejects truncated header`() {
        assertNull(VpnPacket.parseIpv4Udp(ByteArray(19)))
    }

    @Test
    fun `rejects non ipv4 version`() {
        val packet = udpPacket(srcIp, dstIp, 1, 19132, byteArrayOf(1))
        packet[0] = 0x65 // version 6
        assertNull(VpnPacket.parseIpv4Udp(packet))
    }

    @Test
    fun `rejects non udp protocol`() {
        val packet = udpPacket(srcIp, dstIp, 1, 19132, byteArrayOf(1))
        packet[9] = 6 // TCP
        assertNull(VpnPacket.parseIpv4Udp(packet))
    }

    @Test
    fun `rejects zero ihl`() {
        val packet = udpPacket(srcIp, dstIp, 1, 19132, byteArrayOf(1))
        packet[0] = 0x40
        assertNull(VpnPacket.parseIpv4Udp(packet))
    }

    @Test
    fun `rejects udp length shorter than its own header`() {
        val packet = udpPacket(srcIp, dstIp, 1, 19132, byteArrayOf(1))
        // UDP length lives at offset 20 (IP header) + 4 = 24, not at 22,
        // which is the destination port.
        packet[24] = 0
        packet[25] = 0
        assertNull(VpnPacket.parseIpv4Udp(packet))
    }
}
