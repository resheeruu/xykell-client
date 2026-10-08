package dev.xykell.client.runtime.relay

/**
 * RakNet connected-layer codec: ACK/NACK packets, datagrams, frames.
 *
 * Layout cited from CloudburstMC/Network (Apache-2.0): RakDatagramCodec,
 * EncapsulatedPacket, RakAcknowledgeHandler. Clean-room rewrite in Kotlin.
 */

/** 24-bit little-endian index (reliable/sequence/order indices, datagram seq). */
fun RakNetWriter.u24LE(v: Int) {
    if (v !in 0..0xffffff) throw RakNetFormatException("u24 out of range: $v")
    u8(v)
    u8(v ushr 8)
    u8(v ushr 16)
}

fun RakNetReader.u24LE(): Int {
    val a = u8()
    val b = u8()
    val c = u8()
    return a or (b shl 8) or (c shl 16)
}

/** 32-bit big-endian (split partCount / partIndex). */
fun RakNetWriter.i32(v: Int) {
    u8(v ushr 24)
    u8(v ushr 16)
    u8(v ushr 8)
    u8(v)
}

fun RakNetReader.i32(): Int {
    val a = u8()
    val b = u8()
    val c = u8()
    val d = u8()
    return (a shl 24) or (b shl 16) or (c shl 8) or d
}

enum class RakReliability(
    val id: Int,
    val reliable: Boolean,
    val ordered: Boolean,
    val sequenced: Boolean,
) {
    UNRELIABLE(0, false, false, false),
    UNRELIABLE_SEQUENCED(1, false, false, true),
    RELIABLE(2, true, false, false),
    RELIABLE_ORDERED(3, true, true, false),
    RELIABLE_SEQUENCED(4, true, false, true),
    UNRELIABLE_WITH_ACK_RECEIPT(5, false, false, false),
    UNRELIABLE_SEQUENCED_WITH_ACK_RECEIPT(6, false, false, true),
    RELIABLE_WITH_ACK_RECEIPT(7, true, false, false);

    companion object {
        fun fromId(id: Int): RakReliability =
            values().firstOrNull { it.id == id }
                ?: throw RakNetFormatException("bad reliability id: $id")
    }
}

data class RakFrame(
    val reliability: RakReliability,
    val payload: ByteArray,
    val reliableIndex: Int? = null,
    val sequenceIndex: Int? = null,
    val orderingIndex: Int? = null,
    val orderingChannel: Int? = null,
    val splitCount: Int? = null,
    val splitId: Int? = null,
    val splitIndex: Int? = null,
) {
    val isSplit: Boolean get() = splitCount != null

    init {
        if (reliability.reliable && reliableIndex == null) throw RakNetFormatException("reliable frame missing reliableIndex")
        if (!reliability.reliable && reliableIndex != null) throw RakNetFormatException("unreliable frame with reliableIndex")
        if (reliability.sequenced && sequenceIndex == null) throw RakNetFormatException("sequenced frame missing sequenceIndex")
        val needsOrder = reliability.ordered || reliability.sequenced
        if (needsOrder != (orderingIndex != null && orderingChannel != null)) {
            throw RakNetFormatException("ordering fields mismatch for ${reliability.name}")
        }
        val splitParts = listOfNotNull(splitCount, splitId, splitIndex)
        if (splitParts.isNotEmpty() && splitParts.size != 3) throw RakNetFormatException("incomplete split fields")
        if (splitCount != null && splitCount < 1) throw RakNetFormatException("split count must be >= 1: $splitCount")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RakFrame) return false
        return reliability == other.reliability &&
            payload.contentEquals(other.payload) &&
            reliableIndex == other.reliableIndex &&
            sequenceIndex == other.sequenceIndex &&
            orderingIndex == other.orderingIndex &&
            orderingChannel == other.orderingChannel &&
            splitCount == other.splitCount &&
            splitId == other.splitId &&
            splitIndex == other.splitIndex
    }

    override fun hashCode(): Int {
        var h = reliability.hashCode()
        h = 31 * h + payload.contentHashCode()
        h = 31 * h + (reliableIndex ?: 0)
        h = 31 * h + (sequenceIndex ?: 0)
        h = 31 * h + (orderingIndex ?: 0)
        h = 31 * h + (orderingChannel ?: 0)
        h = 31 * h + (splitCount ?: 0)
        h = 31 * h + (splitId ?: 0)
        h = 31 * h + (splitIndex ?: 0)
        return h
    }
}

data class RakDatagram(val flags: Int, val sequenceIndex: Int, val frames: List<RakFrame>)

data class AckRange(val start: Int, val end: Int) {
    val isSingleton: Boolean get() = start == end
}

data class RakAckPacket(val nack: Boolean, val ranges: List<AckRange>)

sealed class ConnectedPacket {
    data class Datagram(val datagram: RakDatagram) : ConnectedPacket()
    data class Ack(val ack: RakAckPacket) : ConnectedPacket()
}

object RakNetConnected {
    const val FLAG_VALID = 0x80
    const val FLAG_ACK = 0x40
    const val FLAG_NACK = 0x20
    const val FLAG_PACKET_PAIR = 0x10
    const val MAX_SPLIT_PARTS = 65536

    fun encodeDatagram(datagram: RakDatagram): ByteArray {
        if (datagram.frames.isEmpty()) throw RakNetFormatException("datagram with no frames")
        val w = RakNetWriter()
        w.u8(datagram.flags)
        w.u24LE(datagram.sequenceIndex)
        for (f in datagram.frames) encodeFrame(w, f)
        return w.toByteArray()
    }

    fun encodeAck(packet: RakAckPacket): ByteArray {
        if (packet.ranges.isEmpty()) throw RakNetFormatException("ack with no ranges")
        if (packet.ranges.size > 0xffff) throw RakNetFormatException("too many ack ranges")
        val w = RakNetWriter()
        w.u8(FLAG_VALID or if (packet.nack) FLAG_NACK else FLAG_ACK)
        w.u16(packet.ranges.size)
        for (r in packet.ranges) {
            if (r.start !in 0..0xffffff || r.end !in 0..0xffffff) {
                throw RakNetFormatException("ack range out of u24: $r")
            }
            if (r.start > r.end) throw RakNetFormatException("ack range inverted: $r")
            w.u8(if (r.isSingleton) 1 else 0)
            w.u24LE(r.start)
            if (!r.isSingleton) w.u24LE(r.end)
        }
        return w.toByteArray()
    }

    fun decode(data: ByteArray): ConnectedPacket {
        if (data.isEmpty()) throw RakNetFormatException("empty packet")
        val flags = data[0].toInt() and 0xff
        if (flags and FLAG_VALID == 0) throw RakNetFormatException("missing valid flag: 0x${flags.toString(16)}")
        return when {
            flags and FLAG_ACK != 0 -> ConnectedPacket.Ack(decodeAck(data, nack = false))
            flags and FLAG_NACK != 0 -> ConnectedPacket.Ack(decodeAck(data, nack = true))
            else -> ConnectedPacket.Datagram(decodeDatagram(data))
        }
    }

    private fun decodeDatagram(data: ByteArray): RakDatagram {
        val r = RakNetReader(data)
        val flags = r.u8()
        val seq = r.u24LE()
        val frames = ArrayList<RakFrame>()
        while (r.remaining > 0) frames.add(decodeFrame(r))
        if (frames.isEmpty()) throw RakNetFormatException("datagram with no frames")
        return RakDatagram(flags, seq, frames)
    }

    private fun decodeAck(data: ByteArray, nack: Boolean): RakAckPacket {
        val r = RakNetReader(data)
        r.u8()
        val count = r.u16()
        val ranges = ArrayList<AckRange>(count)
        repeat(count) {
            val singleton = when (val s = r.u8()) {
                0 -> false
                1 -> true
                else -> throw RakNetFormatException("bad ack singleton flag: $s")
            }
            val start = r.u24LE()
            val end = if (singleton) start else r.u24LE()
            if (start > end) throw RakNetFormatException("inverted ack range: $start..$end")
            ranges.add(AckRange(start, end))
        }
        r.expectEof(if (nack) "nack" else "ack")
        return RakAckPacket(nack, ranges)
    }

    private fun encodeFrame(w: RakNetWriter, f: RakFrame) {
        var flags = f.reliability.id shl 5
        if (f.isSplit) flags = flags or FLAG_PACKET_PAIR
        w.u8(flags)
        w.u16(f.payload.size shl 3)
        f.reliableIndex?.let { w.u24LE(it) }
        f.sequenceIndex?.let { w.u24LE(it) }
        if (f.reliability.ordered || f.reliability.sequenced) {
            w.u24LE(f.orderingIndex ?: throw RakNetFormatException("missing orderingIndex"))
            w.u8(f.orderingChannel ?: throw RakNetFormatException("missing orderingChannel"))
        }
        if (f.isSplit) {
            w.i32(f.splitCount ?: 0)
            w.u16(f.splitId ?: 0)
            w.i32(f.splitIndex ?: 0)
        }
        w.bytes(f.payload)
    }

    private fun decodeFrame(r: RakNetReader): RakFrame {
        val flags = r.u8()
        val reliability = RakReliability.fromId((flags ushr 5) and 0x7)
        val split = flags and FLAG_PACKET_PAIR != 0
        val bitLen = r.u16()
        val payloadBytes = (bitLen + 7) shr 3
        if (payloadBytes > r.remaining) {
            throw RakNetFormatException("frame payload $payloadBytes exceeds remaining ${r.remaining}")
        }
        val reliableIndex = if (reliability.reliable) r.u24LE() else null
        val sequenceIndex = if (reliability.sequenced) r.u24LE() else null
        var orderingIndex: Int? = null
        var orderingChannel: Int? = null
        if (reliability.ordered || reliability.sequenced) {
            orderingIndex = r.u24LE()
            orderingChannel = r.u8()
        }
        var splitCount: Int? = null
        var splitId: Int? = null
        var splitIndex: Int? = null
        if (split) {
            splitCount = r.i32()
            if (splitCount < 1 || splitCount > MAX_SPLIT_PARTS) {
                throw RakNetFormatException("split count out of range: $splitCount")
            }
            splitId = r.u16()
            splitIndex = r.i32()
            if (splitIndex < 0 || splitIndex >= splitCount) {
                throw RakNetFormatException("split index out of range: $splitIndex/$splitCount")
            }
        }
        val payload = r.bytes(payloadBytes)
        return RakFrame(
            reliability = reliability,
            payload = payload,
            reliableIndex = reliableIndex,
            sequenceIndex = sequenceIndex,
            orderingIndex = orderingIndex,
            orderingChannel = orderingChannel,
            splitCount = splitCount,
            splitId = splitId,
            splitIndex = splitIndex,
        )
    }
}
