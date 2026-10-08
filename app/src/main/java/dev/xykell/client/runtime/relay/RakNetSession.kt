package dev.xykell.client.runtime.relay

import java.util.TreeSet

/**
 * One RakNet peer session: our outbound seq space + their inbound seq space.
 *
 * Forwarder keeps peer frames verbatim (single originator per direction, no
 * index collision); only datagram seq numbers are translated per leg.
 *
 * Single-threaded: caller serializes all calls (per leg). No locks.
 */
class RakNetSession(
    private val retransmitTimeoutMs: Long = 300,
    private val maxAttempts: Int = 8,
    private val receiveWindow: Int = 1024,
) {
    private class Pending(
        val frames: List<RakFrame>,
        var sentAtMs: Long,
        var attempts: Int,
    )

    private var nextSendSeq = 0
    private val pending = LinkedHashMap<Int, Pending>()

    private var highestInbound = -1
    private val received = TreeSet<Int>()
    private val ackQueue = TreeSet<Int>()
    private val nackQueue = TreeSet<Int>()
    private val droppedQueue = ArrayDeque<Int>()

    val pendingCount: Int get() = pending.size

    /** New datagram from peer: dedup, queue ack, gap-nack. null = duplicate. */
    fun onInboundDatagram(seq: Int, frames: List<RakFrame>): List<RakFrame>? {
        if (seq < 0 || seq > 0xffffff) throw RakNetFormatException("inbound seq out of u24: $seq")
        val isNew = received.add(seq)
        ackQueue.add(seq)
        if (seq > highestInbound) {
            for (missing in (highestInbound + 1) until seq) nackQueue.add(missing)
            highestInbound = seq
        }
        pruneReceived()
        return if (isNew) frames else null
    }

    /** Peer acked our sent datagrams. */
    fun onInboundAck(ranges: List<AckRange>) {
        for (r in ranges) {
            for (seq in r.start..r.end.coerceAtMost(0xffffff)) pending.remove(seq)
        }
    }

    /** Peer nacks our seqs it missed — retransmit immediately. */
    fun onInboundNack(ranges: List<AckRange>, nowMs: Long): List<RakDatagram> {
        val out = ArrayList<RakDatagram>()
        for (r in ranges) {
            for (seq in r.start..r.end.coerceAtMost(0xffffff)) retransmit(seq, nowMs, out)
        }
        return out
    }

    /** Encode frames for this peer; registers retransmit state. */
    fun outbound(frames: List<RakFrame>, nowMs: Long): RakDatagram {
        if (frames.isEmpty()) throw RakNetFormatException("outbound with no frames")
        val seq = nextSendSeq++
        if (nextSendSeq > 0xffffff) throw RakNetFormatException("send seq exhausted")
        pending[seq] = Pending(frames, sentAtMs = nowMs, attempts = 1)
        return RakDatagram(DATAGRAM_FLAGS, seq, frames)
    }

    /** Overdue retransmits. */
    fun pollRetransmit(nowMs: Long): List<RakDatagram> {
        val due = ArrayList<Int>()
        for ((seq, p) in pending) {
            if (p.sentAtMs in 0 until nowMs - retransmitTimeoutMs) due.add(seq)
        }
        val out = ArrayList<RakDatagram>()
        for (seq in due) retransmit(seq, nowMs, out)
        return out
    }

    /** Batched ACK for peer's seqs we received. */
    fun drainAcks(): RakAckPacket? =
        if (ackQueue.isEmpty()) null
        else RakAckPacket(false, compress(ackQueue.toList())).also { ackQueue.clear() }

    /** NACK for gaps below highest received. */
    fun drainNacks(): RakAckPacket? =
        if (nackQueue.isEmpty()) null
        else RakAckPacket(true, compress(nackQueue.toList())).also { nackQueue.clear() }

    /** Seqs whose retransmit budget ran out. */
    fun drainDropped(): List<Int> =
        if (droppedQueue.isEmpty()) emptyList() else droppedQueue.toList().also { droppedQueue.clear() }

    private fun retransmit(seq: Int, nowMs: Long, out: MutableList<RakDatagram>) {
        val p = pending[seq] ?: return
        if (p.attempts >= maxAttempts) {
            pending.remove(seq)
            droppedQueue.add(seq)
            return
        }
        p.attempts++
        p.sentAtMs = nowMs
        out.add(RakDatagram(DATAGRAM_FLAGS, seq, p.frames))
    }

    private fun pruneReceived() {
        val floor = highestInbound - receiveWindow
        if (floor > 0) received.headSet(floor, true).clear()
    }

    private fun compress(sorted: List<Int>): List<AckRange> {
        val ranges = ArrayList<AckRange>()
        var start = sorted[0]
        var end = start
        for (i in 1 until sorted.size) {
            val v = sorted[i]
            if (v == end + 1) {
                end = v
            } else {
                ranges.add(AckRange(start, end))
                start = v
                end = v
            }
        }
        ranges.add(AckRange(start, end))
        return ranges
    }

    companion object {
        const val DATAGRAM_FLAGS = 0x80
    }
}
