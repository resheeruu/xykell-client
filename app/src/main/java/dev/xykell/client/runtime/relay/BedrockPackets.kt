package dev.xykell.client.runtime.relay

/**
 * Bedrock gameplay packet codec for the relay tap: header + payload decode
 * for the observation-relevant packets. Field layouts mirror pmmp
 * BedrockProtocol (Text 0x09, SetTime 0x0A, MovePlayer 0x13, SetHealth 0x2A):
 * varuint32 header (id = header and 0x3FF), little-endian f32/i32, zigzag
 * signed varint32, length-prefixed UTF-8 strings (varuint32 byte length).
 *
 * Decode only — the relay never sends these. Returns null for unknown ids
 * and malformed bytes alike (one bad packet never throws through the
 * relay thread).
 */
sealed class GamePacket {
    abstract val wireLength: Int

    data class Text(
        val needsTranslation: Boolean,
        val type: Int,
        val source: String?,
        val message: String,
        val params: List<String>,
        override val wireLength: Int,
    ) : GamePacket()

    data class MovePlayer(
        val runtimeId: Long,
        val x: Float,
        val y: Float,
        val z: Float,
        val pitch: Float,
        val yaw: Float,
        val headYaw: Float,
        val mode: Int,
        val onGround: Boolean,
        val teleportCause: Int,
        val teleportItem: Int,
        val tick: Long,
        override val wireLength: Int,
    ) : GamePacket()

    data class SetTime(
        val timeTicks: Int,
        override val wireLength: Int,
    ) : GamePacket()

    data class SetHealth(
        val health: Int,
        override val wireLength: Int,
    ) : GamePacket()
}

object BedrockPackets {

    private const val ID_TEXT = 0x09
    private const val ID_SET_TIME = 0x0A
    private const val ID_MOVE_PLAYER = 0x13
    private const val ID_SET_HEALTH = 0x2A
    private const val ID_MASK = 0x3FF

    fun decode(raw: ByteArray): GamePacket? {
        if (raw.isEmpty()) return null
        return try {
            val header = BedrockHandshake.readVarUInt(raw, 0) ?: return null
            val headerSize = BedrockHandshake.varUIntSize(raw, 0) ?: return null
            val c = Cursor(raw)
            c.p = headerSize
            when (header and ID_MASK) {
                ID_TEXT -> text(c, raw.size)
                ID_SET_TIME -> GamePacket.SetTime(c.zigzagVarInt(), raw.size)
                ID_MOVE_PLAYER -> movePlayer(c, raw.size)
                ID_SET_HEALTH -> GamePacket.SetHealth(c.zigzagVarInt(), raw.size)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun text(c: Cursor, size: Int): GamePacket.Text {
        val needsTranslation = c.u8() != 0
        val category = c.u8()
        val type = c.u8()
        val source = if (category == 1) c.str() else null
        val message = c.str()
        val params = if (category == 2) List(c.varUInt()) { c.str() } else emptyList()
        c.str() // xboxUserId
        c.str() // platformChatId
        if (c.u8() != 0) c.str() // optional filteredMessage
        return GamePacket.Text(needsTranslation, type, source, message, params, size)
    }

    private fun movePlayer(c: Cursor, size: Int): GamePacket.MovePlayer {
        val runtimeId = c.varULong()
        val x = c.leFloat()
        val y = c.leFloat()
        val z = c.leFloat()
        val pitch = c.leFloat()
        val yaw = c.leFloat()
        val headYaw = c.leFloat()
        val mode = c.u8()
        val onGround = c.u8() != 0
        c.varULong() // riding runtime id
        var teleportCause = 0
        var teleportItem = 0
        if (mode == 2) {
            teleportCause = c.leInt()
            teleportItem = c.leInt()
        }
        val tick = c.varULong()
        return GamePacket.MovePlayer(
            runtimeId, x, y, z, pitch, yaw, headYaw,
            mode, onGround, teleportCause, teleportItem, tick, size,
        )
    }

    private class Cursor(private val b: ByteArray) {
        var p: Int = 0

        private fun fail(): Nothing = throw IllegalStateException("truncated packet")

        fun u8(): Int {
            if (p >= b.size) fail()
            return b[p++].toInt() and 0xFF
        }

        fun varUInt(): Int {
            val v = BedrockHandshake.readVarUInt(b, p) ?: fail()
            p += BedrockHandshake.varUIntSize(b, p) ?: fail()
            return v
        }

        fun varULong(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val x = u8()
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                if (shift > 63) fail()
            }
        }

        /** Signed varint32 (zigzag), as used by SetTime/SetHealth. */
        fun zigzagVarInt(): Int {
            val v = varUInt()
            return (v ushr 1) xor -(v and 1)
        }

        fun leInt(): Int {
            if (p + 4 > b.size) fail()
            val v = (b[p].toInt() and 0xFF) or
                ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or
                ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }

        fun leFloat(): Float = Float.fromBits(leInt())

        fun str(): String {
            val n = varUInt()
            if (n < 0 || p + n > b.size) fail()
            val s = String(b, p, n, Charsets.UTF_8)
            p += n
            return s
        }
    }
}
