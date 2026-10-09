package dev.xykell.client.runtime.relay

/**
 * IPv4 + UDP codec for the VPN transport.
 *
 * The TUN file descriptor delivers whole IP packets. To relay a Bedrock
 * session transparently we have to strip the IP and UDP headers on the way
 * out to the real socket, and rebuild them on the way back so the game
 * accepts the reply as if the server had spoken to it directly.
 *
 * Pure Kotlin, no Android framework — every function is host-JVM testable.
 * That matters here: this is the layer where a wrong offset silently
 * corrupts the game session instead of throwing.
 */
object VpnPacket {

    /** Bedrock/RakNet game port. Traffic to this port is what we proxy. */
    const val BEDROCK_PORT = 19132

    const val PROTOCOL_UDP = 17
    private const val IPV4_VERSION_IHL = 0x45
    private const val IP_HEADER_LEN = 20
    private const val UDP_HEADER_LEN = 8

    /** One direction of a proxied flow. */
    data class Datagram(
        val sourceAddress: ByteArray,
        val sourcePort: Int,
        val destAddress: ByteArray,
        val destPort: Int,
        val payload: ByteArray,
    ) {
        /** True when this datagram belongs to the Bedrock game protocol. */
        fun isBedrock(): Boolean =
            destPort == BEDROCK_PORT || sourcePort == BEDROCK_PORT

        // ByteArray in a data class means equals/hashCode compare references by
        // default. These two are compared in tests and used as map keys.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Datagram) return false
            return sourcePort == other.sourcePort &&
                destPort == other.destPort &&
                sourceAddress.contentEquals(other.sourceAddress) &&
                destAddress.contentEquals(other.destAddress) &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = sourceAddress.contentHashCode()
            result = 31 * result + sourcePort
            result = 31 * result + destAddress.contentHashCode()
            result = 31 * result + destPort
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Parse one IP packet off the TUN.
     *
     * Returns null for anything that is not a well-formed IPv4 UDP packet:
     * non-IPv4 version, non-zero IHL, truncated headers, non-UDP protocol,
     * or a length field that disagrees with the buffer. A malformed packet is
     * dropped rather than guessed at — forwarding a misparsed packet would
     * inject garbage into the player's session.
     */
    fun parseIpv4Udp(packet: ByteArray): Datagram? {
        if (packet.size < IP_HEADER_LEN) return null
        if ((packet[0].toInt() and 0xF0) != 0x40) return null // version 4

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < IP_HEADER_LEN || packet.size < ihl) return null
        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

        val totalLength = readU16(packet, 2)
        // Trust the smaller of the declared and actual length so a padded
        // buffer cannot smuggle trailing bytes into the payload.
        val end = if (totalLength in (ihl + UDP_HEADER_LEN)..packet.size) {
            totalLength
        } else {
            packet.size
        }

        val udpStart = ihl
        if (end < udpStart + UDP_HEADER_LEN) return null

        val sourcePort = readU16(packet, udpStart)
        val destPort = readU16(packet, udpStart + 2)
        val udpLength = readU16(packet, udpStart + 4)
        if (udpLength < UDP_HEADER_LEN) return null

        // UDP length covers header + payload. Clamp against what we actually have.
        val payloadStart = udpStart + UDP_HEADER_LEN
        val payloadEnd = minOf(udpStart + udpLength, end)
        if (payloadEnd < payloadStart) return null

        return Datagram(
            sourceAddress = packet.copyOfRange(12, 16),
            sourcePort = sourcePort,
            destAddress = packet.copyOfRange(16, 20),
            destPort = destPort,
            payload = packet.copyOfRange(payloadStart, payloadEnd),
        )
    }

    /**
     * Rebuild a datagram into a full IP+UDP packet for the TUN.
     *
     * [id] is echoed from the inbound packet so replies correlate the way
     * the game expects; the game matches them by IP ID, not by socket.
     */
    fun buildIpv4Udp(dgram: Datagram, id: Int): ByteArray {
        val totalLength = IP_HEADER_LEN + UDP_HEADER_LEN + dgram.payload.size
        val out = ByteArray(totalLength)

        // IPv4 header
        out[0] = IPV4_VERSION_IHL.toByte()
        out[1] = 0 // TOS
        writeU16(out, 2, totalLength)
        writeU16(out, 4, id)
        writeU16(out, 6, 0) // flags + fragment offset
        out[8] = 64 // TTL
        out[9] = PROTOCOL_UDP.toByte()
        writeU16(out, 10, 0) // checksum placeholder, filled below
        dgram.sourceAddress.copyInto(out, 12)
        dgram.destAddress.copyInto(out, 16)
        writeU16(out, 10, checksum(out, 0, IP_HEADER_LEN))

        // UDP header
        val udpStart = IP_HEADER_LEN
        writeU16(out, udpStart, dgram.sourcePort)
        writeU16(out, udpStart + 2, dgram.destPort)
        writeU16(out, udpStart + 4, UDP_HEADER_LEN + dgram.payload.size)
        writeU16(out, udpStart + 6, 0) // UDP checksum: optional for IPv4
        dgram.payload.copyInto(out, destinationOffset = udpStart + UDP_HEADER_LEN)

        return out
    }

    /** Standard one's-complement sum used by the IPv4 header checksum. */
    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun readU16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, at: Int, value: Int) {
        b[at] = ((value shr 8) and 0xFF).toByte()
        b[at + 1] = (value and 0xFF).toByte()
    }
}
