package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.RelayDirection
/**
 * NETWORK batch: `packet_monitor` and `packet_logger`.
 *
 * Neither id is a transform, and neither is faked into one. A monitor counts
 * what passes and a logger prints what passes; neither ever changes a byte, so
 * [transform] forwards everything untouched for both. The real work is the
 * reader pair [monitor] / [logger], which decode one packet and report on it,
 * returning null when there is nothing to report.
 *
 * Decoding is ModuleWire-only. Where a body layout could not be verified from
 * this repo (MovePlayer leads with a 64-bit varlong, which ModuleWire does not
 * expose) the packet is reported as [Observation.Identified] instead of being
 * guessed at. Every read is bounds-checked; a malformed packet yields null or
 * a bare identification, never an exception.
 */
object NetworkModules {

    private const val ID_SET_TIME = 0x0A
    private const val ID_SET_HEALTH = 0x2A

    /** Ids with a real transform. Empty: neither network id rewrites bytes. */
    val IMPLEMENTED: Set<String> = emptySet()

    /** Ids a relay cannot deliver as a transform, with the reason. */
    val IMPOSSIBLE: Map<String, String> = emptyMap()

    /** Ids delivered as readers rather than transforms. */
    val READERS: Set<String> = setOf(
        "xykell.network.packet_monitor",
        "xykell.network.packet_logger",
    )

    /** What a reader reports. */
    sealed class Observation {
        /** Header decoded, body layout not claimed. */
        data class Identified(val id: Int, val header: Int, val bodyLength: Int) : Observation()

        /** SetTime 0x0A body: one zigzag varint32 of server ticks. */
        data class Clock(val timeTicks: Int) : Observation()

        /** SetHealth 0x2A body: one zigzag varint32 of health. */
        data class Health(val health: Int) : Observation()
    }

    /** Forward-only: these ids observe, they never rewrite. */
    fun transform(
        id: String,
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext = ModuleContext(),
    ): List<ByteArray> = listOf(packet)

    /** Structured read of one packet, or null when it cannot be identified. */
    fun monitor(packet: ByteArray): Observation? {
        val header = ModuleWire.header(packet) ?: return null
        val id = header and ModuleWire.ID_MASK
        val bodyStart = ModuleWire.bodyStart(packet) ?: return null
        val bodyLength = packet.size - bodyStart
        return when (id) {
            ID_SET_TIME -> zigzagField(packet, bodyStart)
                ?.let { Observation.Clock(it) }
                ?: Observation.Identified(id, header, bodyLength)
            ID_SET_HEALTH -> zigzagField(packet, bodyStart)
                ?.let { Observation.Health(it) }
                ?: Observation.Identified(id, header, bodyLength)
            else -> Observation.Identified(id, header, bodyLength)
        }
    }

    /** One-line text read of one packet, or null when it cannot be identified. */
    fun logger(packet: ByteArray): String? = when (val o = monitor(packet)) {
        null -> null
        is Observation.Clock -> "set_time ticks=${o.timeTicks}"
        is Observation.Health -> "set_health health=${o.health}"
        is Observation.Identified ->
            "packet id=0x${Integer.toHexString(o.id)} bytes=${o.bodyLength}"
    }

    /** Single leading zigzag varint32 at [offset], or null when truncated. */
    private fun zigzagField(packet: ByteArray, offset: Int): Int? =
        ModuleWire.readVarInt(packet, offset)?.first
}