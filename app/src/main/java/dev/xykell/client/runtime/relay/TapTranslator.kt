package dev.xykell.client.runtime.relay

import dev.xykell.client.runtime.observation.ChatMessage
import dev.xykell.client.runtime.observation.Translated
import dev.xykell.client.runtime.observation.Travelled
import dev.xykell.client.runtime.observation.UnknownFrame
import dev.xykell.client.runtime.observation.Vitals
import kotlin.math.sqrt

/**
 * Turns decrypted session packets into observation Translated outputs plus a
 * position/time snapshot. Stateful per relay session: self runtime id,
 * last position, counters, event-id sequence. Single writer (relay pipe
 * thread); snapshot reads are unlocked per-field reads (ponytail:
 * acceptable staleness — consumer polls at UI rate).
 *
 * Handshake events are ignored here: pre-key tap delivers Handshake
 * only, key acquisition + decrypt is the termination leg's job (P2b,
 * PROXY-DESIGN §6); this translator consumes Batch events once a key
 * exists. Position source: MovePlayer only — PlayerAuthInput 0x90 has
 * no reliable position mode field, deferring it keeps travelMethod
 * honest (ponytail: client-auth servers send self-bound MovePlayer;
 * server-auth accepts unfiltered packets until first client-bound
 * MovePlayer learns the self id).
 */
class TapTranslator {

    /**
     * What the translator is fed. `Batch` is the only variant a terminating
     * session produces: it already holds plaintext, so there is no encrypted or
     * pre-key form to model. `Handshake` survives on the type because the
     * handshake packets are handled by the session and must never reach here.
     */
    sealed class Event {
        data class Handshake(val salt: ByteArray, val x5u: String?) : Event()
        data class Batch(val fromClient: Boolean, val packets: List<ByteArray>) : Event()
    }

    data class Snapshot(
        val timeTicks: Int? = null,
        val health: Int? = null,
        val x: Double? = null,
        val y: Double? = null,
        val z: Double? = null,
        val yawDegrees: Double? = null,
        val selfRuntimeId: Long? = null,
        val chatCount: Long = 0,
        val travelCount: Long = 0,
        val unknownCount: Long = 0,
    )

    companion object {
        const val REASON_TEXT_UNMAPPED = "relay-text-unmapped-type"
        private const val TYPE_CHAT = 1
        private const val TYPE_WHISPER = 7
    }

    private var seq = 0L
    private var chatCount = 0L
    private var travelCount = 0L
    private var unknownCount = 0L
    private var timeTicks: Int? = null
    private var health: Int? = null
    private var selfId: Long? = null
    private var lastX: Double? = null
    private var lastY: Double? = null
    private var lastZ: Double? = null
    private var yawDeg: Double? = null
    private var totalMeters = 0.0

    val snapshot: Snapshot
        get() = Snapshot(
            timeTicks, health, lastX, lastY, lastZ, yawDeg, selfId,
            chatCount, travelCount, unknownCount,
        )

    fun onEvent(ev: Event, atMs: Long): List<Translated> {
        if (ev !is Event.Batch) return emptyList()
        val out = ArrayList<Translated>()
        for (raw in ev.packets) {
            val pkt = BedrockPackets.decode(raw) ?: continue
            when (pkt) {
                is GamePacket.Text -> out.add(text(pkt, atMs))
                is GamePacket.MovePlayer -> move(pkt, ev.fromClient, atMs)?.let { out.add(it) }
                // SetTime and SetHealth become ONE vitals observation rather than
                // two: they arrive on independent cadences, and a snapshot that
                // only ever carried the newer value would drop the other. Only
                // the field this packet actually carried is marked present.
                is GamePacket.SetTime -> {
                    timeTicks = pkt.timeTicks
                    out.add(vitals(atMs, health = null, ticks = pkt.timeTicks))
                }
                is GamePacket.SetHealth -> {
                    health = pkt.health
                    out.add(vitals(atMs, health = pkt.health, ticks = null))
                }
            }
        }
        return out
    }

    private fun nextId(): String = "relay-${++seq}"

    private fun vitals(atMs: Long, health: Int?, ticks: Int?): Vitals =
        Vitals(nextId(), atMs, health, ticks)

    private fun text(pkt: GamePacket.Text, atMs: Long): Translated {
        if (!pkt.needsTranslation &&
            (pkt.type == TYPE_CHAT || pkt.type == TYPE_WHISPER)
        ) {
            chatCount++
            return ChatMessage(nextId(), atMs, pkt.source ?: "", pkt.message)
        }
        unknownCount++
        return UnknownFrame(nextId(), atMs, pkt.wireLength, REASON_TEXT_UNMAPPED)
    }

    private fun move(pkt: GamePacket.MovePlayer, fromClient: Boolean, atMs: Long): Translated? {
        if (fromClient) {
            // Only client-bound traffic proves *our* identity.
            selfId = pkt.runtimeId
        } else {
            val self = selfId
            if (self != null && pkt.runtimeId != self) return null
            // Self unknown: accept as heuristic, never learn id server-bound.
        }
        val x = pkt.x.toDouble()
        val y = pkt.y.toDouble()
        val z = pkt.z.toDouble()
        val hasBaseline = lastX != null
        val delta = if (hasBaseline) {
            val dx = x - lastX!!
            val dy = y - lastY!!
            val dz = z - lastZ!!
            sqrt(dx * dx + dy * dy + dz * dz)
        } else {
            0.0
        }
        lastX = x
        lastY = y
        lastZ = z
        yawDeg = pkt.yaw.toDouble()
        val isTeleport = pkt.mode != 0
        if (delta <= 0.0 && !isTeleport) return null // baseline or duplicate
        totalMeters += delta
        travelCount++
        return Travelled(
            nextId(), atMs, x, y, z, yawDeg!!,
            totalMeters, pkt.mode,
        )
    }
}
