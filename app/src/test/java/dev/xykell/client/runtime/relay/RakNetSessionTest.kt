package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RakNetSessionTest {

    private fun frame(b: Byte) = RakFrame(RakReliability.UNRELIABLE, byteArrayOf(b))

    @Test
    fun `new inbound datagram forwards frames and queues ack`() {
        val s = RakNetSession()
        val out = s.onInboundDatagram(0, listOf(frame(1)))
        assertEquals(listOf(frame(1)), out)
        val ack = s.drainAcks()!!
        assertEquals(listOf(AckRange(0, 0)), ack.ranges)
        assertEquals(false, ack.nack)
        assertNull(s.drainAcks())
    }

    @Test
    fun `contiguous acks compress to one range`() {
        val s = RakNetSession()
        s.onInboundDatagram(0, listOf(frame(1)))
        s.onInboundDatagram(1, listOf(frame(2)))
        s.onInboundDatagram(2, listOf(frame(3)))
        assertEquals(listOf(AckRange(0, 2)), s.drainAcks()!!.ranges)
    }

    @Test
    fun `duplicate inbound returns null but re-acks`() {
        val s = RakNetSession()
        s.onInboundDatagram(0, listOf(frame(1)))
        assertNull(s.onInboundDatagram(0, listOf(frame(1))))
        assertEquals(listOf(AckRange(0, 0)), s.drainAcks()!!.ranges)
    }

    @Test
    fun `gap queues nack for missing seq`() {
        val s = RakNetSession()
        s.onInboundDatagram(0, listOf(frame(1)))
        s.onInboundDatagram(2, listOf(frame(3)))
        val nack = s.drainNacks()!!
        assertTrue(nack.nack)
        assertEquals(listOf(AckRange(1, 1)), nack.ranges)
        assertEquals(listOf(AckRange(0, 0), AckRange(2, 2)), s.drainAcks()!!.ranges)
    }

    @Test
    fun `outbound assigns increasing seqs and tracks pending`() {
        val s = RakNetSession()
        assertEquals(0, s.outbound(listOf(frame(1)), nowMs = 0).sequenceIndex)
        assertEquals(1, s.outbound(listOf(frame(2)), nowMs = 0).sequenceIndex)
        assertEquals(2, s.pendingCount)
    }

    @Test
    fun `ack clears pending and stops retransmit`() {
        val s = RakNetSession()
        s.outbound(listOf(frame(1)), nowMs = 0)
        s.onInboundAck(listOf(AckRange(0, 0)))
        assertEquals(0, s.pendingCount)
        assertTrue(s.pollRetransmit(nowMs = 1000).isEmpty())
    }

    @Test
    fun `overdue pending retransmits with same seq`() {
        val s = RakNetSession(retransmitTimeoutMs = 300)
        s.outbound(listOf(frame(1)), nowMs = 0)
        assertTrue(s.pollRetransmit(nowMs = 100).isEmpty())
        val out = s.pollRetransmit(nowMs = 400)
        assertEquals(1, out.size)
        assertEquals(0, out[0].sequenceIndex)
        assertEquals(1, s.pendingCount)
        assertTrue(s.pollRetransmit(nowMs = 500).isEmpty()) // clock reset
    }

    @Test
    fun `retransmit budget eventually drops and reports`() {
        val s = RakNetSession(retransmitTimeoutMs = 1, maxAttempts = 3)
        s.outbound(listOf(frame(1)), nowMs = 0)
        assertEquals(1, s.pollRetransmit(nowMs = 10).size) // attempts 1 -> 2
        assertEquals(1, s.pollRetransmit(nowMs = 20).size) // attempts 2 -> 3
        assertTrue(s.pollRetransmit(nowMs = 30).isEmpty()) // attempts >= 3 -> drop
        assertEquals(listOf(0), s.drainDropped())
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun `nack retransmits listed seq immediately`() {
        val s = RakNetSession()
        s.outbound(listOf(frame(1)), nowMs = 0)
        s.outbound(listOf(frame(2)), nowMs = 0)
        val out = s.onInboundNack(listOf(AckRange(1, 1)), nowMs = 5)
        assertEquals(1, out.size)
        assertEquals(1, out[0].sequenceIndex)
        assertEquals(2, s.pendingCount)
        assertTrue(s.onInboundNack(listOf(AckRange(9, 9)), nowMs = 5).isEmpty())
    }

    @Test
    fun `late ack after drop is ignored`() {
        val s = RakNetSession(retransmitTimeoutMs = 1, maxAttempts = 1)
        s.outbound(listOf(frame(1)), nowMs = 0)
        assertTrue(s.pollRetransmit(nowMs = 10).isEmpty())
        s.onInboundAck(listOf(AckRange(0, 0)))
        assertEquals(0, s.pendingCount)
        assertEquals(listOf(0), s.drainDropped())
    }

    @Test
    fun `rejects inbound seq outside u24`() {
        val s = RakNetSession()
        try {
            s.onInboundDatagram(-1, listOf(frame(1)))
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("u24"))
        }
    }

    @Test
    fun `rejects outbound with no frames`() {
        val s = RakNetSession()
        try {
            s.outbound(emptyList(), nowMs = 0)
            throw AssertionError("expected format exception")
        } catch (expected: RakNetFormatException) {
            assertTrue(expected.message!!.contains("no frames"))
        }
    }
}
