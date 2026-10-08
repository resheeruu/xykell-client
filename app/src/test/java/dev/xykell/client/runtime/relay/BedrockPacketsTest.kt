package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Wire-exact fixtures against the pmmp BedrockProtocol layouts
 * (Text 0x09 / MovePlayer 0x13 / SetTime 0x0A / SetHealth 0x2A).
 */
class BedrockPacketsTest {

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

    /** pmmp VarInt::writeSignedInt zigzag. */
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

    private fun cat(
        type: Int,
        category: Int,
        source: String?,
        message: String,
        params: List<String> = emptyList(),
        needsTranslation: Boolean = false,
        filtered: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u8(if (needsTranslation) 1 else 0))
        out.write(u8(category))
        out.write(u8(type))
        if (source != null) out.write(str(source))
        out.write(str(message))
        if (category == 2) {
            out.write(varUInt(params.size))
            params.forEach { out.write(str(it)) }
        }
        out.write(str("")) // xboxUserId
        out.write(str("")) // platformChatId
        if (filtered != null) {
            out.write(u8(1))
            out.write(str(filtered))
        } else {
            out.write(u8(0))
        }
        return out.toByteArray()
    }

    private fun move(
        runtimeId: Long,
        x: Float, y: Float, z: Float,
        pitch: Float, yaw: Float, headYaw: Float,
        mode: Int,
        onGround: Boolean = true,
        riding: Long = 0L,
        cause: Int = 0,
        item: Int = 0,
        tick: Long = 0L,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varULong(runtimeId))
        out.write(leFloat(x)); out.write(leFloat(y)); out.write(leFloat(z))
        out.write(leFloat(pitch)); out.write(leFloat(yaw)); out.write(leFloat(headYaw))
        out.write(u8(mode))
        out.write(u8(if (onGround) 1 else 0))
        out.write(varULong(riding))
        if (mode == 2) {
            out.write(leInt(cause))
            out.write(leInt(item))
        }
        out.write(varULong(tick))
        return out.toByteArray()
    }

    @Test
    fun header_highFlagBitsStillDecodeId() {
        val raw = varUInt(0x09 or 0x8000) + cat(1, 1, "Steve", "hi")
        val pkt = BedrockPackets.decode(raw)
        assertNotNull(pkt)
        assertEquals(true, pkt is GamePacket.Text)
    }

    @Test
    fun text_chatType1RoundTrip() {
        val raw = varUInt(0x09) + cat(1, 1, "Steve", "hi")
        val pkt = BedrockPackets.decode(raw)
        require(pkt is GamePacket.Text)
        assertEquals(false, pkt.needsTranslation)
        assertEquals(1, pkt.type)
        assertEquals("Steve", pkt.source)
        assertEquals("hi", pkt.message)
        assertEquals(emptyList<String>(), pkt.params)
        assertEquals(raw.size, pkt.wireLength)
    }

    @Test
    fun text_systemType6MessageOnly() {
        val raw = varUInt(0x09) + cat(6, 0, null, "hello")
        val pkt = BedrockPackets.decode(raw)
        require(pkt is GamePacket.Text)
        assertEquals(6, pkt.type)
        assertEquals(null, pkt.source)
        assertEquals("hello", pkt.message)
    }

    @Test
    fun text_translationType2CarriesParams() {
        val raw = varUInt(0x09) + cat(2, 2, null, "chat.type.announcement", listOf("one", "two"))
        val pkt = BedrockPackets.decode(raw)
        require(pkt is GamePacket.Text)
        assertEquals(2, pkt.type)
        assertEquals(listOf("one", "two"), pkt.params)
    }

    @Test
    fun text_needsTranslationFlagPreserved() {
        val raw = varUInt(0x09) + cat(1, 1, "Steve", "hi", needsTranslation = true)
        val pkt = BedrockPackets.decode(raw)
        require(pkt is GamePacket.Text)
        assertEquals(true, pkt.needsTranslation)
    }

    @Test
    fun movePlayer_mode0RoundTrip() {
        val raw = varUInt(0x13) + move(
            42L, 1.5f, 64f, -3f, 10f, 90f, 91f, mode = 0, tick = 1234L,
        )
        val pkt = BedrockPackets.decode(raw)
        require(pkt is GamePacket.MovePlayer)
        assertEquals(42L, pkt.runtimeId)
        assertEquals(1.5f, pkt.x, 0f)
        assertEquals(64f, pkt.y, 0f)
        assertEquals(-3f, pkt.z, 0f)
        assertEquals(10f, pkt.pitch, 0f)
        assertEquals(90f, pkt.yaw, 0f)
        assertEquals(91f, pkt.headYaw, 0f)
        assertEquals(0, pkt.mode)
        assertEquals(true, pkt.onGround)
        assertEquals(1234L, pkt.tick)
        assertEquals(raw.size, pkt.wireLength)
    }

    @Test
    fun movePlayer_mode2TeleportFields() {
        val raw = varUInt(0x13) + move(
            7L, 0f, 0f, 0f, 0f, 0f, 0f, mode = 2, cause = 1, item = 5, tick = 99L,
        )
        val pkt = BedrockPackets.decode(raw)
        require(pkt is GamePacket.MovePlayer)
        assertEquals(2, pkt.mode)
        assertEquals(1, pkt.teleportCause)
        assertEquals(5, pkt.teleportItem)
        assertEquals(99L, pkt.tick)
    }

    @Test
    fun setTime_zigzagDecode() {
        // zigzag(6000) = 12000 on the wire.
        val raw = varUInt(0x0A) + zigzagVarInt(6000)
        val pkt = BedrockPackets.decode(raw)
        require(pkt is GamePacket.SetTime)
        assertEquals(6000, pkt.timeTicks)
    }

    @Test
    fun setHealth_zigzagNegativeDecode() {
        // zigzag(-7) = 13 on the wire: decoder must be true zigzag, not raw.
        val raw = varUInt(0x2A) + zigzagVarInt(-7)
        val pkt = BedrockPackets.decode(raw)
        require(pkt is GamePacket.SetHealth)
        assertEquals(-7, pkt.health)
    }

    @Test
    fun unknownId_returnsNull() {
        assertNull(BedrockPackets.decode(varUInt(0x77) + ByteArray(4)))
    }

    @Test
    fun truncatedText_returnsNull() {
        val body = cat(1, 1, "Steve", "hi").copyOf(8)
        assertNull(BedrockPackets.decode(varUInt(0x09) + body))
    }

    @Test
    fun truncatedMove_returnsNull() {
        val body = move(42L, 1f, 2f, 3f, 0f, 0f, 0f, mode = 0).copyOf(12)
        assertNull(BedrockPackets.decode(varUInt(0x13) + body))
    }

    @Test
    fun empty_returnsNull() {
        assertNull(BedrockPackets.decode(ByteArray(0)))
    }
}
