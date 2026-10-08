package dev.xykell.client.runtime.relay

import java.util.TreeMap

/**
 * Pure-Kotlin RakNet endpoint covering the offline handshake (both roles) and
 * the connected phase: CR/CRA/NIC, connected ping/pong, split reassembly,
 * ordered delivery, outbound split, ACK/NACK/retransmit via [RakSession]… no,
 * via [RakNetSession].
 *
 * No sockets, no threads: the caller feeds datagrams and ticks and sends the
 * returned bytes. Layouts cited from CloudburstMC/Network (Apache-2.0)
 * handlers (RakClient/ServerOfflineHandler, RakClient/ServerOnlineInitialHandler,
 * ConnectedPingHandler, RakConstants). Clean-room.
 */
class RakNetEndpoint(
    private val mode: Mode,
    private val guid: Long,
    private val serverAddress: RakNetAddress? = null,
    private val advertisement: String = "",
    private val mtuCap: Int = 1400,
    private val retryIntervalMs: Long = 1000,
    private val maxAttempts: Int = 10,
    private val sessionTimeoutMs: Long = 10000,
    private val maxSplitParts: Int = 1024,
    private val maxReassembledBytes: Int = 2 * 1024 * 1024,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class Mode { CLIENT, SERVER }
    enum class State { HANDSHAKE_1, HANDSHAKE_2, ONLINE_INITIAL, CONNECTED, CLOSED }

    data class Result(
        val sends: List<ByteArray> = emptyList(),
        val payloads: List<ByteArray> = emptyList(),
        val disconnected: Boolean = false,
    )

    private class Denial(val id: Int) : Exception()

    private class PendingSplit(val count: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
        var total = 0
    }

    var state: State = State.HANDSHAKE_1
        private set

    var lastError: String? = null
        private set

    val connected: Boolean get() = state == State.CONNECTED

    private var session: RakNetSession? = null
    private var clientGuid = 0L
    private var mtu = mtuCap
    private var cookie = 0
    private var security = false
    private var attempts = 0
    private var lastRetryMs = Long.MIN_VALUE / 2
    private var lastActivityMs = nowMs()
    private var pingTimeSeen = 0L

    private var nextReliableIndex = 0
    private var nextOrderingIndex = 0
    private var nextSplitId = 0

    private val splits = HashMap<Int, PendingSplit>()
    private val ordered = HashMap<Int, TreeMap<Int, ByteArray>>()
    private val nextOrdered = HashMap<Int, Int>()
    private val sequencedLast = HashMap<Int, Int>()

    // ---------------------------------------------------------------- offline

    private fun tryHandleOffline(data: ByteArray, from: RakNetAddress): Result? {
        if (data.isEmpty()) return null
        val id = data[0].toInt() and 0xff
        if (id in RAW_DENIAL_IDS) return handleDenial(id)
        if (id !in OFFLINE_IDS) return null
        val p = try {
            RakNetOffline.decode(data)
        } catch (e: RakNetFormatException) {
            return Result() // id matched but malformed: drop
        }
        val now = nowMs()
        val sends = ArrayList<ByteArray>()
        when (p) {
            is OfflinePacket.Ping -> {
                if (mode == Mode.SERVER && state != State.CLOSED) {
                    sends += offline(OfflinePacket.Pong(now, guid, advertisement))
                }
            }
            is OfflinePacket.Pong -> Unit // client has no use for pongs yet
            is OfflinePacket.OpenRequest1 -> {
                if (mode == Mode.SERVER && state != State.CLOSED) {
                    mtu = minOf(p.mtu, mtuCap)
                    sends += offline(OfflinePacket.OpenReply1(guid, security = false, mtu = mtu))
                }
            }
            is OfflinePacket.OpenReply1 -> {
                if (mode == Mode.CLIENT && state == State.HANDSHAKE_1) {
                    mtu = minOf(p.mtu, mtuCap)
                    security = p.security
                    cookie = p.cookie
                    state = State.HANDSHAKE_2
                    sends += request2()
                }
            }
            is OfflinePacket.OpenRequest2 -> {
                if (mode == Mode.SERVER && state != State.CLOSED) {
                    if (p.mtu !in RakNetOffline.MIN_MTU..mtuCap) return Result()
                    if (session != null) {
                        // Retry after a lost reply2: resend rather than deny.
                        sends += offline(OfflinePacket.OpenReply2(guid, from, mtu, encryption = false))
                    } else {
                        clientGuid = p.guid
                        mtu = p.mtu
                        session = RakNetSession()
                        state = State.HANDSHAKE_2
                        sends += offline(OfflinePacket.OpenReply2(guid, from, mtu, encryption = false))
                    }
                }
            }
            is OfflinePacket.OpenReply2 -> {
                if (mode == Mode.CLIENT && state == State.HANDSHAKE_2) {
                    if (p.encryption) return fail("server requires security")
                    mtu = minOf(p.mtu, mtuCap)
                    session = RakNetSession()
                    state = State.ONLINE_INITIAL
                    sends += frame(connectionRequest(), RakReliability.RELIABLE_ORDERED)
                }
            }
        }
        return Result(sends)
    }

    private fun request2(): ByteArray {
        val addr = serverAddress ?: return fail("no server address").let { ByteArray(0) }
        return offline(
            OfflinePacket.OpenRequest2(
                addr, mtu, guid,
                cookie = if (security) cookie else null,
            ),
        )
    }

    private fun offline(p: OfflinePacket): ByteArray = RakNetOffline.encode(p)

    private fun handleDenial(id: Int): Result {
        lastError = DENIAL_MESSAGES[id] ?: "connection denied"
        state = State.CLOSED
        return Result(disconnected = true)
    }

    // -------------------------------------------------------------- connected

    fun onDatagram(data: ByteArray, from: RakNetAddress): Result {
        if (state == State.CLOSED) return Result()
        tryHandleOffline(data, from)?.let { return it }
        val sess = session ?: return Result()
        val sends = ArrayList<ByteArray>()
        val payloads = ArrayList<ByteArray>()
        var disconnected = false
        try {
            lastActivityMs = nowMs()
            when (val cp = RakNetConnected.decode(data)) {
                is ConnectedPacket.Ack -> {
                    if (cp.ack.nack) {
                        for (d in sess.onInboundNack(cp.ack.ranges, nowMs())) {
                            sends += RakNetConnected.encodeDatagram(d)
                        }
                    } else {
                        sess.onInboundAck(cp.ack.ranges)
                    }
                }
                is ConnectedPacket.Datagram -> {
                    val frames = sess.onInboundDatagram(cp.datagram.sequenceIndex, cp.datagram.frames)
                    if (frames != null) {
                        for (f in frames) {
                            for (complete in acceptFrame(f)) {
                                val r = dispatch(complete, from)
                                payloads += r.payloads
                                sends += r.sends
                                if (r.disconnected) disconnected = true
                            }
                        }
                    }
                }
            }
            sends += drainControl(sess)
        } catch (e: Denial) {
            return handleDenial(e.id)
        } catch (e: RakNetFormatException) {
            return Result(sends) // hostile or foreign bytes: drop
        }
        if (disconnected) state = State.CLOSED
        return Result(sends, payloads, disconnected)
    }

    private fun dispatch(p: ByteArray, from: RakNetAddress): Result {
        if (p.isEmpty()) return Result()
        val sends = ArrayList<ByteArray>()
        val now = nowMs()
        when {
            p[0].toInt() == 0x00 && p.size == 9 -> { // connected ping → pong
                val w = RakNetWriter()
                w.u8(0x03)
                for (i in 1..8) w.u8(p[i].toInt())
                w.u64(now)
                sends += frame(w.toByteArray(), RakReliability.UNRELIABLE)
            }
            p[0].toInt() == 0x03 && p.size == 17 -> Unit // connected pong
            p[0].toInt() == 0x15 -> return Result(disconnected = true)
            p[0].toInt() in RAW_DENIAL_IDS -> throw Denial(p[0].toInt() and 0xff)
            mode == Mode.SERVER && p[0].toInt() == 0x09 &&
                (state == State.HANDSHAKE_2 || state == State.ONLINE_INITIAL) -> {
                val r = RakNetReader(p)
                r.u8()
                val crGuid = r.u64()
                val ts = r.u64()
                val sec = r.u8()
                if (crGuid != clientGuid || sec != 0) {
                    val w = RakNetWriter()
                    w.u8(0x11)
                    w.bytes(RakNetOffline.MAGIC)
                    w.u64(guid)
                    sends += frame(w.toByteArray(), RakReliability.RELIABLE_ORDERED)
                    lastError = "connection request failed"
                    state = State.CLOSED
                    return Result(sends, disconnected = true)
                }
                sends += frame(accepted(from, ts), RakReliability.UNRELIABLE)
                state = State.ONLINE_INITIAL
            }
            mode == Mode.SERVER && p[0].toInt() == 0x13 && state == State.ONLINE_INITIAL -> {
                state = State.CONNECTED // new-incoming-connection: content ignored
            }
            mode == Mode.CLIENT && p[0].toInt() == 0x10 && state == State.ONLINE_INITIAL -> {
                sends += frame(newIncomingConnection(p), RakReliability.RELIABLE_ORDERED)
                state = State.CONNECTED
            }
            (state == State.ONLINE_INITIAL || state == State.CONNECTED) -> {
                return Result(payloads = listOf(p))
            }
        }
        return Result(sends = sends)
    }

    private fun accepted(from: RakNetAddress, ts: Long): ByteArray {
        val w = RakNetWriter()
        w.u8(0x10)
        from.encodeInto(w)
        w.u16(0) // system index
        repeat(10) { i ->
            val local = if (i == 0) RakNetAddress("127.0.0.1", 0) else RakNetAddress("0.0.0.0", 0)
            local.encodeInto(w)
        }
        w.u64(ts)
        w.u64(nowMs())
        return w.toByteArray()
    }

    private fun connectionRequest(): ByteArray {
        val w = RakNetWriter()
        w.u8(0x09)
        w.u64(guid)
        w.u64(nowMs())
        w.u8(0) // security = false
        return w.toByteArray()
    }

    private fun newIncomingConnection(acceptedPayload: ByteArray): ByteArray {
        val r = RakNetReader(acceptedPayload)
        r.u8()
        val peer = RakNetAddress.decodeFrom(r) // client address (server's view of us)
        r.u16()
        r.skip(minOf(r.remaining - 16, 10 * 7).coerceAtLeast(0))
        pingTimeSeen = r.u64()
        r.u64()
        val w = RakNetWriter()
        w.u8(0x13)
        val remote = serverAddress ?: peer
        remote.encodeInto(w)
        repeat(10) { RakNetAddress("0.0.0.0", 0).encodeInto(w) }
        w.u64(pingTimeSeen)
        w.u64(nowMs())
        return w.toByteArray()
    }

    // -------------------------------------------------------------- inbound frames

    private fun acceptFrame(f: RakFrame): List<ByteArray> {
        var payload = f.payload
        var reliability = f.reliability
        var orderIdx = f.orderingIndex
        var channel = f.orderingChannel
        if (f.isSplit) {
            val count = f.splitCount!!
            val id = f.splitId!!
            val idx = f.splitIndex!!
            if (count > maxSplitParts) throw RakNetFormatException("split count $count exceeds $maxSplitParts")
            if (splits.size > MAX_CONCURRENT_SPLITS && id !in splits) {
                throw RakNetFormatException("too many concurrent splits")
            }
            val cur = splits.getOrPut(id) { PendingSplit(count) }
            if (cur.count != count) throw RakNetFormatException("split count mismatch for id $id")
            val part = cur.parts[idx]
            if (part != null) return emptyList() // duplicate part
            if (cur.total + f.payload.size > maxReassembledBytes) {
                throw RakNetFormatException("reassembly exceeds $maxReassembledBytes bytes")
            }
            cur.parts[idx] = f.payload
            cur.total += f.payload.size
            cur.received++
            if (cur.received < count) return emptyList()
            splits.remove(id)
            val joined = ByteArray(cur.total)
            var off = 0
            for (q in cur.parts) {
                System.arraycopy(q!!, 0, joined, off, q.size)
                off += q.size
            }
            payload = joined
            reliability = f.reliability
            orderIdx = f.orderingIndex
            channel = f.orderingChannel
        }
        return when {
            reliability.ordered -> orderedDeliver(channel!!, orderIdx!!, payload)
            reliability.sequenced -> {
                val c = channel!!
                val idx = orderIdx!!
                if (idx > (sequencedLast[c] ?: -1)) {
                    sequencedLast[c] = idx
                    listOf(payload)
                } else {
                    emptyList()
                }
            }
            else -> listOf(payload)
        }
    }

    private fun orderedDeliver(channel: Int, index: Int, payload: ByteArray): List<ByteArray> {
        val next = nextOrdered.getOrPut(channel) { 0 }
        if (index < next) return emptyList() // stale duplicate
        val q = ordered.getOrPut(channel) { TreeMap() }
        if (q.size >= MAX_ORDERING_BACKLOG) throw RakNetFormatException("ordering backlog on channel $channel")
        q[index] = payload
        val out = ArrayList<ByteArray>()
        var expected = next
        while (true) {
            val h = q.remove(expected) ?: break
            out.add(h)
            expected++
        }
        nextOrdered[channel] = expected
        return out
    }

    // ------------------------------------------------------------------ output

    /** Application payload outbound; split across datagrams at the negotiated MTU. */
    fun send(payload: ByteArray): Result {
        val sess = session ?: return Result()
        if (state == State.CLOSED) return Result()
        val sends = ArrayList<ByteArray>()
        val chunk = mtu - DATAGRAM_HEADER - MAX_ENCAPSULATED_HEADER
        if (payload.size <= chunk) {
            sends += frame(payload, RakReliability.RELIABLE_ORDERED)
        } else {
            val orderIdx = nextOrderingIndex++
            val splitId = nextSplitId++ and 0xffff
            val count = (payload.size + chunk - 1) / chunk
            for (i in 0 until count) {
                val from = i * chunk
                val part = payload.copyOfRange(from, minOf(from + chunk, payload.size))
                sends += frame(
                    part,
                    RakReliability.RELIABLE_ORDERED,
                    orderingIndex = orderIdx,
                    split = Triple(count, splitId, i),
                )
            }
        }
        return Result(sends = sends)
    }

    /** Connected ping (UNRELIABLE, like Cloudburst keep-alives). */
    fun sendPing(): Result {
        if (session == null || state == State.CLOSED) return Result()
        val w = RakNetWriter()
        w.u8(0x00)
        w.u64(nowMs())
        return Result(sends = listOf(frame(w.toByteArray(), RakReliability.UNRELIABLE)))
    }

    /** Retries, retransmits, control drains, session timeout. */
    fun onTick(): Result {
        if (state == State.CLOSED) return Result()
        val now = nowMs()
        val sends = ArrayList<ByteArray>()
        var disconnected = false
        if (state == State.CONNECTED && now - lastActivityMs > sessionTimeoutMs) {
            lastError = "session timeout"
            state = State.CLOSED
            return Result(disconnected = true)
        }
        if (mode == Mode.CLIENT && (state == State.HANDSHAKE_1 || state == State.HANDSHAKE_2)) {
            if (now - lastRetryMs >= retryIntervalMs) {
                lastRetryMs = now
                if (state == State.HANDSHAKE_1) {
                    attempts++
                    if (attempts > maxAttempts) {
                        lastError = "connect timeout"
                        state = State.CLOSED
                        return Result(disconnected = true)
                    }
                    val ladder = MTU_SIZES[minOf(attempts / 4, MTU_SIZES.size - 1)]
                    mtu = minOf(mtu, ladder)
                    sends += offline(OfflinePacket.OpenRequest1(RakNetOffline.PROTOCOL_VERSION, mtu))
                } else {
                    sends += request2()
                }
            }
        }
        val sess = session
        if (sess != null && state != State.HANDSHAKE_1) {
            for (d in sess.pollRetransmit(now)) sends += RakNetConnected.encodeDatagram(d)
            sends += drainControl(sess)
        }
        return Result(sends = sends, disconnected = disconnected)
    }

    private fun drainControl(sess: RakNetSession): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        sess.drainAcks()?.let { out += RakNetConnected.encodeAck(it) }
        sess.drainNacks()?.let { out += RakNetConnected.encodeAck(it) }
        return out
    }

    private fun frame(
        payload: ByteArray,
        reliability: RakReliability,
        orderingIndex: Int? = null,
        split: Triple<Int, Int, Int>? = null,
    ): ByteArray {
        val sess = session ?: throw RakNetFormatException("no session")
        val reliableIndex = if (reliability.reliable) nextReliableIndex++ and 0xffffff else null
        val order = if (reliability.ordered || reliability.sequenced) {
            orderingIndex ?: nextOrderingIndex++
        } else {
            null
        }
        val f = RakFrame(
            reliability = reliability,
            payload = payload,
            reliableIndex = reliableIndex,
            orderingIndex = order,
            orderingChannel = if (order != null) 0 else null,
            splitCount = split?.first,
            splitId = split?.second,
            splitIndex = split?.third,
        )
        return RakNetConnected.encodeDatagram(sess.outbound(listOf(f), nowMs()))
    }

    private fun fail(message: String): Result {
        lastError = message
        state = State.CLOSED
        return Result(disconnected = true)
    }

    companion object {
        private const val DATAGRAM_HEADER = 4
        private const val MAX_ENCAPSULATED_HEADER = 28
        private const val MAX_CONCURRENT_SPLITS = 16
        private const val MAX_ORDERING_BACKLOG = 256

        /** {1400, 1200, 576} — Cloudburst RakConstants.MTU_SIZES. */
        private val MTU_SIZES = intArrayOf(1400, 1200, 576)

        private val OFFLINE_IDS = setOf(0x01, 0x05, 0x06, 0x07, 0x08, 0x1c)
        private val RAW_DENIAL_IDS = setOf(0x11, 0x12, 0x14, 0x19, 0x1a)
        private val DENIAL_MESSAGES = mapOf(
            0x11 to "connection request failed",
            0x12 to "already connected",
            0x14 to "no free incoming connections",
            0x19 to "incompatible raknet version",
            0x1a to "address recently connected",
        )
    }
}
