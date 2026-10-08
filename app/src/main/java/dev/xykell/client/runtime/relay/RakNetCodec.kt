package dev.xykell.client.runtime.relay

/**
 * RakNet offline-handshake codec — pure Kotlin, no Android/JVM I/O.
 *
 * Wire formats are the publicly documented RakNet offline packets as
 * implemented by CloudburstMC/Network (Apache-2.0, `RakUtils.writeAddress`)
 * and the RakNet spec. Clean-room: no code copied from GPLv3 clients.
 */

class RakNetFormatException(message: String) : IllegalArgumentException(message)

/** Bounds-checked big-endian writer with geometric growth. */
class RakNetWriter {
    private var buf = ByteArray(64)
    private var len = 0

    private fun need(n: Int) {
        if (len + n <= buf.size) return
        var cap = buf.size
        while (cap < len + n) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun u8(v: Int) {
        need(1)
        buf[len++] = (v and 0xff).toByte()
    }

    fun u16(v: Int) {
        require(v in 0..0xffff) { "u16 out of range: $v" }
        u8(v ushr 8)
        u8(v)
    }

    /** 64 raw bits (RakNet GUID / timestamps are opaque i64). */
    fun u64(v: Long) {
        need(8)
        for (shift in 56 downTo 0 step 8) buf[len++] = ((v ushr shift) and 0xff).toByte()
    }

    fun bytes(a: ByteArray) {
        need(a.size)
        System.arraycopy(a, 0, buf, len, a.size)
        len += a.size
    }

    fun size(): Int = len

    fun toByteArray(): ByteArray = buf.copyOf(len)
}

/** Bounds-checked big-endian reader; every underflow throws. */
class RakNetReader(private val buf: ByteArray) {
    var pos = 0
        private set

    val remaining: Int get() = buf.size - pos

    private fun take(n: Int) {
        if (n < 0 || pos + n > buf.size) throw RakNetFormatException("truncated: need $n at $pos of ${buf.size}")
    }

    fun u8(): Int {
        take(1)
        return buf[pos++].toInt() and 0xff
    }

    fun u16(): Int {
        val hi = u8()
        val lo = u8()
        return (hi shl 8) or lo
    }

    fun u64(): Long {
        take(8)
        var v = 0L
        repeat(8) { v = (v shl 8) or (buf[pos++].toLong() and 0xff) }
        return v
    }

    fun bytes(n: Int): ByteArray {
        take(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun skip(n: Int) {
        take(n)
        pos += n
    }

    fun expectMagic() {
        val m = bytes(RakNetOffline.MAGIC.size)
        if (!m.contentEquals(RakNetOffline.MAGIC)) throw RakNetFormatException("bad magic")
    }

    fun expectEof(what: String) {
        if (remaining != 0) throw RakNetFormatException("trailing bytes in $what: $remaining")
    }
}

/**
 * IPv4-only SystemAddress: version 4, octets complemented (~x) in network
 * order, port BE — matches Cloudburst `RakUtils.writeAddress`/`readAddress`.
 */
data class RakNetAddress(val host: String, val port: Int) {

    fun encodeInto(w: RakNetWriter) {
        val parts = host.split(".")
        if (parts.size != 4) throw RakNetFormatException("not an IPv4 host: $host")
        val octets = parts.map { p ->
            val v = p.toIntOrNull() ?: throw RakNetFormatException("bad IPv4 octet: $p")
            if (v !in 0..255) throw RakNetFormatException("bad IPv4 octet: $p")
            v
        }
        if (port !in 0..0xffff) throw RakNetFormatException("port out of range: $port")
        w.u8(4)
        for (i in 0..3) w.u8(octets[i].inv())
        w.u16(port)
    }

    companion object {
        fun decodeFrom(r: RakNetReader): RakNetAddress {
            val version = r.u8()
            if (version != 4) throw RakNetFormatException("unsupported address version $version (IPv6 not implemented)")
            val b = r.bytes(4)
            val host = (0..3).joinToString(".") { (b[it].toInt().inv() and 0xff).toString() }
            return RakNetAddress(host, r.u16())
        }
    }
}

sealed class OfflinePacket {
    data class Ping(val time: Long) : OfflinePacket()
    data class Pong(val time: Long, val guid: Long, val motd: String) : OfflinePacket()
    data class OpenRequest1(val protocolVersion: Int, val mtu: Int) : OfflinePacket()
    data class OpenReply1(val guid: Long, val security: Boolean, val mtu: Int, val cookie: Int = 0) : OfflinePacket()
    data class OpenRequest2(val server: RakNetAddress, val mtu: Int, val guid: Long, val cookie: Int? = null) : OfflinePacket()
    data class OpenReply2(val guid: Long, val client: RakNetAddress, val mtu: Int, val encryption: Boolean) : OfflinePacket()
}

object RakNetOffline {
    const val MIN_MTU = 576
    const val MAX_MTU = 1500
    const val PROTOCOL_VERSION = 11

    private const val ID_PING = 0x01
    private const val ID_PONG = 0x1c
    private const val ID_OPEN_REQ1 = 0x05
    private const val ID_OPEN_REPLY1 = 0x06
    private const val ID_OPEN_REQ2 = 0x07
    private const val ID_OPEN_REPLY2 = 0x08

    /** IPv4 + UDP header overhead RakNet excludes from the datagram size. */
    const val IP_UDP_OVERHEAD = 28

    val MAGIC = byteArrayOf(
        0x00, 0xff.toByte(), 0xff.toByte(), 0x00,
        0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
        0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(),
        0x12, 0x34, 0x56, 0x78,
    )

    private fun checkMtu(mtu: Int) {
        if (mtu !in MIN_MTU..MAX_MTU) throw RakNetFormatException("mtu out of range: $mtu")
    }

    fun encode(p: OfflinePacket): ByteArray {
        val w = RakNetWriter()
        when (p) {
            is OfflinePacket.Ping -> {
                w.u8(ID_PING)
                w.u64(p.time)
                w.bytes(MAGIC)
            }            is OfflinePacket.Pong -> {
                w.u8(ID_PONG)
                w.u64(p.time)
                w.u64(p.guid)
                w.bytes(MAGIC)
                val m = p.motd.toByteArray(Charsets.UTF_8)
                if (m.size > 0xffff) throw RakNetFormatException("motd too long")
                w.u16(m.size)
                w.bytes(m)
            }
            is OfflinePacket.OpenRequest1 -> {
                checkMtu(p.mtu)
                w.u8(ID_OPEN_REQ1)
                w.bytes(MAGIC)
                w.u8(p.protocolVersion)
                // Datagram size the server sees is mtu - (20 IP + 8 UDP) overhead;
                // Cloudburst computes mtu = data.size + 28, so pad to mtu - 28.
                val header = 1 + MAGIC.size + 1
                repeat(p.mtu - IP_UDP_OVERHEAD - header) { w.u8(0) }
            }
            is OfflinePacket.OpenReply1 -> {
                checkMtu(p.mtu)
                w.u8(ID_OPEN_REPLY1)
                w.bytes(MAGIC)
                w.u64(p.guid)
                w.u8(if (p.security) 1 else 0)
                if (p.security) w.i32(p.cookie)
                w.u16(p.mtu)
            }
            is OfflinePacket.OpenRequest2 -> {
                checkMtu(p.mtu)
                w.u8(ID_OPEN_REQ2)
                w.bytes(MAGIC)
                p.cookie?.let { cookie ->
                    w.i32(cookie)
                    w.u8(0) // challenge = false
                }
                p.server.encodeInto(w)
                w.u16(p.mtu)
                w.u64(p.guid)
            }
            is OfflinePacket.OpenReply2 -> {
                checkMtu(p.mtu)
                w.u8(ID_OPEN_REPLY2)
                w.bytes(MAGIC)
                w.u64(p.guid)
                p.client.encodeInto(w)
                w.u16(p.mtu)
                w.u8(if (p.encryption) 1 else 0)
            }
        }
        return w.toByteArray()
    }

    fun decode(data: ByteArray): OfflinePacket {
        if (data.isEmpty()) throw RakNetFormatException("empty packet")
        val r = RakNetReader(data)
        return when (val id = r.u8()) {
            ID_PING -> {
                val time = r.u64()
                r.expectMagic()
                // Trailing bytes tolerated: clients may append a session-stride int.
                OfflinePacket.Ping(time)
            }
            ID_PONG -> {
                val time = r.u64()
                val guid = r.u64()
                r.expectMagic()
                val len = r.u16()
                val motd = String(r.bytes(len), Charsets.UTF_8)
                r.expectEof("pong")
                OfflinePacket.Pong(time, guid, motd)
            }
            ID_OPEN_REQ1 -> {
                r.expectMagic()
                val proto = r.u8()
                // Remainder is MTU padding by design; server-side mtu = size + 28.
                val mtu = data.size + IP_UDP_OVERHEAD
                checkMtu(mtu)
                OfflinePacket.OpenRequest1(proto, mtu)
            }
            ID_OPEN_REPLY1 -> {
                r.expectMagic()
                val guid = r.u64()
                val security = when (val s = r.u8()) {
                    0 -> false
                    1 -> true
                    else -> throw RakNetFormatException("bad security flag: $s")
                }
                val cookie = if (security) r.i32() else 0
                val mtu = r.u16()
                checkMtu(mtu)
                r.expectEof("reply1")
                OfflinePacket.OpenReply1(guid, security, mtu, cookie)
            }
            ID_OPEN_REQ2 -> {
                r.expectMagic()
                val server = RakNetAddress.decodeFrom(r)
                val mtu = r.u16()
                checkMtu(mtu)
                val guid = r.u64()
                r.expectEof("req2")
                OfflinePacket.OpenRequest2(server, mtu, guid)
            }
            ID_OPEN_REPLY2 -> {
                r.expectMagic()
                val guid = r.u64()
                val client = RakNetAddress.decodeFrom(r)
                val mtu = r.u16()
                checkMtu(mtu)
                val encryption = when (val e = r.u8()) {
                    0 -> false
                    1 -> true
                    else -> throw RakNetFormatException("bad encryption flag: $e")
                }
                r.expectEof("reply2")
                OfflinePacket.OpenReply2(guid, client, mtu, encryption)
            }
            else -> throw RakNetFormatException("unknown offline packet id: 0x${id.toString(16)}")
        }
    }
}
