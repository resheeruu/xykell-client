package dev.xykell.client.runtime.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RakNetConnectedTest {

    private fun datagramOf(vararg frames: RakFrame, seq: Int = 1, flags: Int = 0x80): RakDatagram =
        RakDatagram(flags, seq, frames.toList())

    private fun decodeDatagram(data: ByteArray): RakDatagram =
        (RakNetConnected.decode(data) as ConnectedPacket.Datagram).datagram

    private fun decodeAck(data: ByteArray): RakAckPacket =
        (RakNetConnected.decode(data) as ConnectedPacket.Ack).ack

    @Test
    fun `datagram round trips multiple frames`() {
        val f1 = RakFrame(
            RakReliability.RELIABLE_ORDERED, "hello".toByteArray(),
            reliableIndex = 5, orderingIndex = 9, orderingChannel = 0,
        )
        val f2 = RakFrame(RakReliability.UNRELIABLE, byteArrayOf(1, 2, 3))
        val wire = RakNetConnected.encodeDatagram(datagramOf(f1, f2, seq = 0x010203))
        val decoded = decodeDatagram(wire)
        assertEquals(0x010203, decoded.sequenceIndex)
        assertEquals(listOf(f1, f2), decoded.frames)
    }

    @Test
    fun `plain reliable frame has no ordering fields`() {
        val f = RakFrame(RakReliability.RELIABLE, byteArrayOf(9), reliableIndex = 1)
        val wire = RakNetConnected.encodeDatagram(datagramOf(f))
        val decoded = decodeDatagram(wire)
        assertEquals(f, decoded.frames[0])
        assertEquals(4 + 3 + 3 + 1, wire.size) // datagram hdr + flags+len u16 + reliableIndex u24 + payload
    }

    @Test
    fun `sequenced frame carries reliability sequence and ordering indices`() {
        val f = RakFrame(
            RakReliability.RELIABLE_SEQUENCED, byteArrayOf(7),
            reliableIndex = 3, sequenceIndex = 4, orderingIndex = 5, orderingChannel = 2,
        )
        val decoded = decodeDatagram(RakNetConnected.encodeDatagram(datagramOf(f)))
        assertEquals(f, decoded.frames[0])
    }

    @Test
    fun `split frame round trips parts`() {
        val f = RakFrame(
            RakReliability.RELIABLE, ByteArray(16) { it.toByte() },
            reliableIndex = 42, splitCount = 3, splitId = 7, splitIndex = 1,
        )
        val decoded = decodeDatagram(RakNetConnected.encodeDatagram(datagramOf(f)))
        assertEquals(f, decoded.frames[0])
        assertTrue(decoded.frames[0].isSplit)
    }

    @Test
    fun `empty payload frame round trips`() {
        val f = RakFrame(RakReliability.RELIABLE_ORDERED, ByteArray(0), reliableIndex = 1, orderingIndex = 0, orderingChannel = 0)
        val decoded = decodeDatagram(RakNetConnected.encodeDatagram(datagramOf(f)))
        assertEquals(f, decoded.frames[0])
    }

    @Test
    fun `ack round trips singleton and range`() {
        val packet = RakAckPacket(false, listOf(AckRange(4, 4), AckRange(10, 20)))
        val decoded = decodeAck(RakNetConnected.encodeAck(packet))
        assertEquals(packet, decoded)
    }

    @Test
    fun `ack wire layout matches flag count and little-endian ranges`() {
        val wire = RakNetConnected.encodeAck(RakAckPacket(false, listOf(AckRange(4, 4), AckRange(10, 20))))
        val expected = byteArrayOf(
            0xc0.toByte(), 0x00, 0x02,
            0x01, 0x04, 0x00, 0x00,
            0x00, 0x0a, 0x00, 0x00, 0x14, 0x00, 0x00,
        )
        assertArrayEquals(expected, wire)
    }

    @Test
    fun `nack flag decodes as nack`() {
        val packet = RakAckPacket(true, listOf(AckRange(6, 6)))
        val decoded = decodeAck(RakNetConnected.encodeAck(packet))
        assertTrue(decoded.nack)
        assertEquals(listOf(AckRange(6, 6)), decoded.ranges)
    }

    @Test
    fun `reliability id 7 decodes as reliable ack-receipt`() {
        val w = RakNetWriter()
        w.u8(0x80)
        w.u24LE(0)
        w.u8(7 shl 5) // RELIABLE_WITH_ACK_RECEIPT
        w.u16(8) // 1 byte payload in bits
        w.u24LE(42)
        w.u8(0xaa)
        val frame = decodeDatagram(w.toByteArray()).frames[0]
        assertEquals(RakReliability.RELIABLE_WITH_ACK_RECEIPT, frame.reliability)
        assertTrue(frame.reliability.reliable)
        assertEquals(42, frame.reliableIndex)
        assertArrayEquals(byteArrayOf(0xaa.toByte()), frame.payload)
    }

    @Test
    fun `rejects packet without valid flag`() {
        try {
            RakNetConnected.decode(byteArrayOf(0x00, 0x01))
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("valid"))
        }
    }

    @Test
    fun `rejects inverted ack range`() {
        val w = RakNetWriter()
        w.u8(0xc0)
        w.u16(1)
        w.u8(0)
        w.u24LE(20)
        w.u24LE(10)
        try {
            decodeAck(w.toByteArray())
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("inverted"))
        }
    }

    @Test
    fun `rejects frame payload larger than buffer`() {
        val w = RakNetWriter()
        w.u8(0x80)
        w.u24LE(0)
        w.u8(3 shl 5) // RELIABLE_ORDERED
        w.u16(800) // claims 100 bytes
        w.u24LE(1)
        w.u24LE(2)
        w.u8(0)
        w.bytes(ByteArray(4))
        try {
            decodeDatagram(w.toByteArray())
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("exceeds remaining"))
        }
    }

    @Test
    fun `rejects datagram with no frames`() {
        val w = RakNetWriter()
        w.u8(0x80)
        w.u24LE(0)
        try {
            decodeDatagram(w.toByteArray())
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("no frames"))
        }
    }

    @Test
    fun `rejects split index beyond part count`() {
        val w = RakNetWriter()
        w.u8(0x80)
        w.u24LE(0)
        w.u8((2 shl 5) or 0x10) // RELIABLE + split
        w.u16(8)
        w.u24LE(1)
        w.i32(2) // count
        w.u16(0) // id
        w.i32(5) // index 5 of 2 — invalid
        w.u8(0xff)
        try {
            decodeDatagram(w.toByteArray())
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("split index"))
        }
    }

    @Test
    fun `encode rejects sequence index beyond u24`() {
        try {
            RakNetConnected.encodeDatagram(datagramOf(RakFrame(RakReliability.UNRELIABLE, byteArrayOf(1)), seq = 0x1000000))
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("u24"))
        }
    }
}
