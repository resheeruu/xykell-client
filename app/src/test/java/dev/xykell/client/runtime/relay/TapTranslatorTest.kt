package dev.xykell.client.runtime.relay

import dev.xykell.client.runtime.observation.ChatMessage
import dev.xykell.client.runtime.observation.Travelled
import dev.xykell.client.runtime.observation.UnknownFrame
import dev.xykell.client.runtime.observation.Vitals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TapTranslatorTest {

    private fun varUInt(v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x80.inv() == 0) {
                out.write(x)
                return out.toByteArray()
            }
            out.write((x and 0x7f) or 0x80)
            x = x ushr 7
        }
    }

    private fun varULong(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7fL.inv() == 0L) {
                out.write(x.toInt())
                return out.toByteArray()
            }
            out.write(((x and 0x7f) or 0x80L).toInt())
            x = x ushr 7
        }
    }

    private fun zigzagVarInt(v: Int): ByteArray = varUInt((v shl 1) xor (v shr 31))

    private fun u8(v: Int): ByteArray = byteArrayOf(v.toByte())

    private fun leInt(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(), ((v ushr 24) and 0xff).toByte(),
    )

    private fun leFloat(v: Float): ByteArray = leInt(v.toRawBits())

    private fun str(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return varUInt(b.size) + b
    }

    private fun textPacket(
        type: Int,
        category: Int,
        source: String?,
        message: String,
        needsTranslation: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varUInt(0x09))
        out.write(u8(if (needsTranslation) 1 else 0))
        out.write(u8(category))
        out.write(u8(type))
        if (source != null) out.write(str(source))
        out.write(str(message))
        out.write(str(""))
        out.write(str(""))
        out.write(u8(0))
        return out.toByteArray()
    }

    private fun movePacket(
        runtimeId: Long,
        x: Float, y: Float, z: Float,
        yaw: Float,
        mode: Int,
        tick: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varUInt(0x13))
        out.write(varULong(runtimeId))
        out.write(leFloat(x)); out.write(leFloat(y)); out.write(leFloat(z))
        out.write(leFloat(0f)); out.write(leFloat(yaw)); out.write(leFloat(yaw))
        out.write(u8(mode))
        out.write(u8(1))
        out.write(varULong(0L))
        if (mode == 2) {
            out.write(leInt(0)); out.write(leInt(0))
        }
        out.write(varULong(tick))
        return out.toByteArray()
    }

    private fun batch(
        tr: TapTranslator,
        fromClient: Boolean,
        atMs: Long,
        vararg packets: ByteArray,
    ) = tr.onEvent(TapTranslator.Event.Batch(fromClient, packets.toList()), atMs)

    @Test
    fun clientChatBecomesChatMessage() {
        val tr = TapTranslator()
        val out = batch(
            tr, false, 1000L,
            textPacket(1, 1, "Steve", "hi"),
        )
        assertEquals(1, out.size)
        val chat = out[0] as ChatMessage
        assertEquals("relay-1", chat.eventId)
        assertEquals(1000L, chat.observedAtMs)
        assertEquals("Steve", chat.sender)
        assertEquals("hi", chat.message)
        assertEquals(1L, tr.snapshot.chatCount)
    }

    @Test
    fun systemTextBecomesUnknownFrame() {
        val tr = TapTranslator()
        val packet = textPacket(6, 0, null, "hello")
        val out = batch(tr, false, 5L, packet)
        assertEquals(1, out.size)
        val unk = out[0] as UnknownFrame
        assertEquals("relay-1", unk.eventId)
        assertEquals(packet.size, unk.wireLength)
        assertEquals("relay-text-unmapped-type", unk.reason)
        assertEquals(1L, tr.snapshot.unknownCount)
    }

    @Test
    fun needsTranslationChatIsUnknownNotChat() {
        val tr = TapTranslator()
        val out = batch(
            tr, false, 5L,
            textPacket(1, 1, "Steve", "hi", needsTranslation = true),
        )
        assertEquals(1, out.size)
        assertTrue(out[0] is UnknownFrame)
        assertEquals(0L, tr.snapshot.chatCount)
    }

    @Test
    fun selfTravelAccumulatesMeters() {
        val tr = TapTranslator()
        val first = batch(tr, true, 10L, movePacket(42L, 0f, 0f, 0f, 0f, mode = 0, tick = 1L))
        assertEquals(emptyList<dev.xykell.client.runtime.observation.Translated>(), first)
        assertEquals(0.0, tr.snapshot.x!!, 1e-9)

        val second = batch(tr, true, 20L, movePacket(42L, 3f, 4f, 0f, 90f, mode = 0, tick = 2L))
        assertEquals(1, second.size)
        val travel = second[0] as Travelled
        assertEquals("relay-1", travel.eventId)
        assertEquals(3.0, travel.x, 1e-9)
        assertEquals(4.0, travel.y, 1e-9)
        assertEquals(0.0, travel.z, 1e-9)
        assertEquals(90.0, travel.yawDegrees, 1e-9)
        assertEquals(5.0, travel.metersTravelled, 1e-9)
        assertEquals(0, travel.travelMethod)
        assertEquals(42L, tr.snapshot.selfRuntimeId)
        assertEquals(1L, tr.snapshot.travelCount)
    }

    @Test
    fun serverBoundOtherPlayerFiltered() {
        val tr = TapTranslator()
        batch(tr, true, 10L, movePacket(42L, 0f, 0f, 0f, 0f, mode = 0, tick = 1L))
        val out = batch(tr, false, 20L, movePacket(7L, 50f, 0f, 0f, 0f, mode = 0, tick = 2L))
        assertEquals(emptyList<dev.xykell.client.runtime.observation.Translated>(), out)
        assertEquals(0.0, tr.snapshot.x!!, 1e-9)
        assertEquals(0L, tr.snapshot.travelCount)
    }

    @Test
    fun serverBoundTeleportOfSelfEmits() {
        val tr = TapTranslator()
        batch(tr, true, 10L, movePacket(42L, 0f, 0f, 0f, 0f, mode = 0, tick = 1L))
        val out = batch(tr, false, 20L, movePacket(42L, 100f, 0f, 0f, 45f, mode = 2, tick = 2L))
        assertEquals(1, out.size)
        val travel = out[0] as Travelled
        assertEquals(100.0, travel.metersTravelled, 1e-9)
        assertEquals(2, travel.travelMethod)
        assertEquals(100.0, tr.snapshot.x!!, 1e-9)
    }

    @Test
    fun zeroDeltaMode0DuplicateSkipped() {
        val tr = TapTranslator()
        batch(tr, true, 10L, movePacket(42L, 0f, 0f, 0f, 0f, mode = 0, tick = 1L))
        val out = batch(tr, true, 20L, movePacket(42L, 0f, 0f, 0f, 0f, mode = 0, tick = 2L))
        assertEquals(emptyList<dev.xykell.client.runtime.observation.Translated>(), out)
        assertEquals(0L, tr.snapshot.travelCount)
    }

    @Test
    fun zeroDeltaMode2StillEmits() {
        val tr = TapTranslator()
        batch(tr, true, 10L, movePacket(42L, 0f, 0f, 0f, 0f, mode = 0, tick = 1L))
        val out = batch(tr, false, 20L, movePacket(42L, 0f, 0f, 0f, 0f, mode = 2, tick = 2L))
        assertEquals(1, out.size)
        assertEquals(0.0, (out[0] as Travelled).metersTravelled, 1e-9)
    }

    @Test
    fun serverBoundBeforeSelfKnownAcceptedButNotLearned() {
        val tr = TapTranslator()
        // First observed packet is baseline: position stored, no emit.
        val first = batch(tr, false, 10L, movePacket(7L, 1f, 2f, 3f, 0f, mode = 0, tick = 1L))
        assertTrue(first.isEmpty())
        assertEquals(1.0, tr.snapshot.x!!, 1e-9)
        assertNull(tr.snapshot.selfRuntimeId)
        // Second server-bound move still accepted while self unknown, still not learned.
        val second = batch(tr, false, 20L, movePacket(7L, 5f, 6f, 3f, 0f, mode = 0, tick = 2L))
        assertEquals(1, second.size)
        assertNull(tr.snapshot.selfRuntimeId)
        // First client packet then learns the real self id.
        batch(tr, true, 30L, movePacket(42L, 5f, 6f, 3f, 0f, mode = 0, tick = 3L))
        assertEquals(42L, tr.snapshot.selfRuntimeId)
    }

    @Test
    fun setTimeAndHealthUpdateSnapshotAndEmitVitals() {
        val tr = TapTranslator()
        val out1 = batch(tr, false, 10L, varUInt(0x0A) + zigzagVarInt(6000))
        val out2 = batch(tr, false, 20L, varUInt(0x2A) + zigzagVarInt(18))
        assertEquals(6000, tr.snapshot.timeTicks)
        assertEquals(18, tr.snapshot.health)
        assertEquals(0L, tr.snapshot.unknownCount)

        // One vitals observation per packet, each carrying ONLY the field that
        // packet actually had. SetTime does not also report health, and SetHealth
        // does not also report the clock: the two arrive on independent
        // cadences, and a merged value would invent one the server never sent.
        assertEquals(1, out1.size)
        val clock = out1.single() as Vitals
        assertEquals(6000, clock.timeTicks)
        assertNull(clock.health)
        assertEquals(1, out2.size)
        val hp = out2.single() as Vitals
        assertEquals(18, hp.health)
        assertNull(hp.timeTicks)
    }

    @Test
    fun zeroHealthIsAnObservationNotAnAbsence() {
        val tr = TapTranslator()
        val out = batch(tr, false, 10L, varUInt(0x2A) + zigzagVarInt(0))
        val hp = out.single() as Vitals
        assertEquals(0, hp.health)
        assertNull(hp.timeTicks)
    }

    @Test
    fun handshakeEventIgnored() {
        val tr = TapTranslator()
        val out = tr.onEvent(TapTranslator.Event.Handshake(ByteArray(16), null), 1L)
        assertTrue(out.isEmpty())
    }

    @Test
    fun unknownPacketIdIgnored() {
        val tr = TapTranslator()
        val out = batch(tr, false, 10L, varUInt(0x77) + ByteArray(4) { 1 })
        assertTrue(out.isEmpty())
        assertEquals(0L, tr.snapshot.unknownCount)
    }

    @Test
    fun eventIdsShareSequence() {
        val tr = TapTranslator()
        batch(tr, false, 10L, textPacket(1, 1, "Steve", "hi"))
        batch(tr, true, 20L, movePacket(42L, 0f, 0f, 0f, 0f, mode = 0, tick = 1L))
        val out = batch(tr, true, 30L, movePacket(42L, 6f, 8f, 0f, 0f, mode = 0, tick = 2L))
        assertEquals("relay-2", out[0].eventId)
    }

    @Test
    fun snapshotCountersFollowOutputs() {
        val tr = TapTranslator()
        batch(tr, false, 10L, textPacket(1, 1, "Steve", "hi"))
        batch(tr, false, 20L, textPacket(6, 0, null, "sys"))
        batch(tr, true, 30L, movePacket(42L, 0f, 0f, 0f, 0f, mode = 0, tick = 1L))
        batch(tr, true, 40L, movePacket(42L, 1f, 0f, 0f, 0f, mode = 0, tick = 2L))
        val snap = tr.snapshot
        assertEquals(1L, snap.chatCount)
        assertEquals(1L, snap.unknownCount)
        assertEquals(1L, snap.travelCount)
    }

    @Test
    fun needsTranslationSystemTextStillUnknownReason() {
        val tr = TapTranslator()
        val out = batch(
            tr, false, 5L,
            textPacket(6, 0, null, "sys", needsTranslation = true),
        )
        assertEquals(1, out.size)
        assertTrue(out[0] is UnknownFrame)
        assertEquals("relay-text-unmapped-type", (out[0] as UnknownFrame).reason)
    }
}
