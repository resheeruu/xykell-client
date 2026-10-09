package dev.xykell.client.runtime.modules

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte-level Bedrock wire helpers for module transforms.
 *
 * A packet is `varuint32 header || body`, where the header carries the id in
 * its low 10 bits (`header and 0x3FF`) and the sender/recipient sub-client
 * ids above it. Modules change body fields only, so they must be able to read
 * a field at an offset and put the same packet back together — that is all
 * this exposes. Nothing here allocates a packet class or holds state.
 *
 * Every read is bounds-checked and returns null rather than throwing, so a
 * malformed packet from the wire never takes the relay down.
 *
 * The one packet-specific section is PlayerAuthInput 0x90's `input_data`: two
 * category objects need to read and rewrite the same list, and a second copy of
 * that parse would be the kind of drift this class exists to prevent.
 */
object ModuleWire {

    const val ID_MASK = 0x3FF

    /** Packet id from a raw packet, or null when too short. */
    fun id(raw: ByteArray): Int? {
        val header = header(raw) ?: return null
        return header and ID_MASK
    }

    /** Full header varuint, or null when truncated. */
    fun header(raw: ByteArray): Int? = readVarUInt(raw, 0)?.first

    /** Byte offset just past the header, i.e. where the body starts. */
    fun bodyStart(raw: ByteArray): Int? {
        val size = varUIntSize(raw, 0) ?: return null
        return size
    }

    fun readVarUInt(buf: ByteArray, offset: Int): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var i = offset
        while (i < buf.size && shift < 28) {
            val b = buf[i].toInt() and 0xff
            value = value or ((b and 0x7f) shl shift)
            i++
            if (b and 0x80 == 0) return value to i
            shift += 7
        }
        return null
    }

    /** Zigzag-encoded signed varint32. */
    fun readVarInt(buf: ByteArray, offset: Int): Pair<Int, Int>? {
        val (raw, next) = readVarUInt(buf, offset) ?: return null
        return ((raw ushr 1) xor -(raw and 1)) to next
    }

    fun varUIntSize(buf: ByteArray, offset: Int): Int? {
        var i = offset
        while (i < buf.size) {
            if (buf[i].toInt() and 0x80 == 0) return i - offset + 1
            i++
        }
        return null
    }

    fun writeVarUInt(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if (v and 0x7f.inv() == 0) {
                out.write(v)
                return out.toByteArray()
            }
            out.write((v and 0x7f) or 0x80)
            v = v ushr 7
        }
    }

    fun writeVarInt(value: Int): ByteArray = writeVarUInt((value shl 1) xor (value shr 31))

    /**
     * LEB128 64-bit varint -- the framing a runtime id uses on the wire.
     * Matches how EntityTable reads one, so a module and the table agree on
     * where the next field starts.
     */
    fun readVarLong(buf: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < buf.size && shift < 64) {
            val b = buf[i].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    /** varuint32-length-prefixed UTF-8 string, as value + offset past it. */
    fun readVarString(buf: ByteArray, offset: Int): Pair<String, Int>? {
        val (len, next) = readVarUInt(buf, offset) ?: return null
        if (len <= 0 || next + len > buf.size) return null
        return String(buf, next, len, Charsets.UTF_8) to (next + len)
    }

    fun writeVarString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return writeVarUInt(bytes.size) + bytes
    }

    fun readF32LE(buf: ByteArray, offset: Int): Pair<Float, Int>? {
        if (offset + 4 > buf.size) return null
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float to (offset + 4)
    }

    fun writeF32LE(value: Float): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()

    fun readF64LE(buf: ByteArray, offset: Int): Pair<Double, Int>? {
        if (offset + 8 > buf.size) return null
        return ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.LITTLE_ENDIAN).double to (offset + 8)
    }

    fun readU64LE(buf: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset + 8 > buf.size) return null
        return ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long to (offset + 8)
    }

    fun writeU64LE(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    fun readI32LE(buf: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset + 4 > buf.size) return null
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int to (offset + 4)
    }

    fun readU16LE(buf: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset + 2 > buf.size) return null
        return (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8) to (offset + 2)
    }

    /** Little-endian signed byte, the bedrock `lbyte`. */
    fun readByte(buf: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset + 1 > buf.size) return null
        return buf[offset].toInt() to (offset + 1)
    }

    fun readBool(buf: ByteArray, offset: Int): Pair<Boolean, Int>? =
        readByte(buf, offset)?.let { (it.first != 0) to it.second }

    /** Reassemble a packet from its original header plus new body bytes. */
    fun build(header: Int, vararg body: ByteArray): ByteArray =
        writeVarUInt(header) + body.fold(ByteArray(0)) { acc, b -> acc + b }

    /** A single unsigned byte literal, for the fixed-width flag fields. */
    fun byte(value: Int): ByteArray = byteArrayOf((value and 0xff).toByte())

    fun concat(parts: List<ByteArray>): ByteArray =
        parts.fold(ByteArray(0)) { acc, b -> acc + b }

    /**
     * Copy [raw] with [from]..[to) replaced by [replacement]. Used by modules
     * that rewrite one field and must leave every other byte identical.
     */
    fun splice(raw: ByteArray, from: Int, to: Int, replacement: ByteArray): ByteArray {
        if (from < 0 || to > raw.size || from > to) return raw
        val out = ByteArray(raw.size - (to - from) + replacement.size)
        System.arraycopy(raw, 0, out, 0, from)
        System.arraycopy(replacement, 0, out, from, replacement.size)
        System.arraycopy(raw, to, out, from + replacement.size, raw.size - to)
        return out
    }

    /** Copy [raw] with `body[from]` overwritten in place (same-width fields). */
    fun put(raw: ByteArray, at: Int, bytes: ByteArray): ByteArray {
        if (at < 0 || at + bytes.size > raw.size) return raw
        val out = raw.copyOf()
        System.arraycopy(bytes, 0, out, at, bytes.size)
        return out
    }

    // --- PlayerAuthInput 0x90 `input_data` ---------------------------------
    //
    // Upstream (bedrock 1.26.45): "As of 1.26.40 it is an optional list of the
    // ordinals below" — `input_data?: InputData[]varint`, i.e. a presence byte,
    // then a varint count, then that many zigzag32 ordinals. It is NOT the
    // pre-1.26.40 bitfield any more, so every read below goes through the
    // presence byte and the count. Nothing here falls back to treating those
    // bytes as a mask: a caller that cannot parse the list must forward the
    // packet, because a bitfield rewrite over a list would corrupt it.

    /** Bytes of fixed-width fields ahead of `input_data`. */
    const val PLAYER_AUTH_INPUT_INPUT_DATA_OFFSET = 32

    /** PlayerAuthInput's `position.y`, four bytes ahead of `input_data`. */
    const val PLAYER_AUTH_INPUT_POSITION_Y_OFFSET = 12

    const val INPUT_SPRINT_DOWN = 4
    const val INPUT_SNEAKING = 8
    const val INPUT_SNEAK_DOWN = 9
    const val INPUT_SPRINTING = 20
    const val INPUT_PERSIST_SNEAK = 24
    const val INPUT_START_SPRINTING = 25
    const val INPUT_STOP_SPRINTING = 26
    const val INPUT_START_SNEAKING = 27
    const val INPUT_STOP_SNEAKING = 28
    const val INPUT_BLOCK_BREAKING_DELAY_ENABLED = 48

    /**
     * The `input_data` ordinals at [offset] and the offset just past the field,
     * or null when the packet is truncated or the count overruns it. The list is
     * empty when the presence byte is 0.
     */
    fun readInputData(buf: ByteArray, offset: Int): Pair<List<Int>, Int>? {
        val (present, afterPresent) = readBool(buf, offset) ?: return null
        if (!present) return emptyList<Int>() to afterPresent
        val (count, afterCount) = readVarUInt(buf, afterPresent) ?: return null
        if (count > buf.size - afterCount) return null
        val out = ArrayList<Int>(count)
        var at = afterCount
        repeat(count) {
            val (value, next) = readVarInt(buf, at) ?: return null
            out.add(value)
            at = next
        }
        return out to at
    }

    /** The same optional-list form, presence byte included. */
    fun writeInputData(values: List<Int>): ByteArray {
        if (values.isEmpty()) return byteArrayOf(0)
        return byteArrayOf(1) + writeVarUInt(values.size) + concat(values.map { writeVarInt(it) })
    }

    /**
     * Copy of [raw] with its `input_data` ordinals replaced by [keep] added and
     * [drop] removed. Returns [raw] untouched when the ordinal set already
     * matches, when the list cannot be parsed, or when the packet is not the
     * PlayerAuthInput this expects — a caller must never guess the list's shape.
     */
    fun setInputData(raw: ByteArray, keep: List<Int>, drop: List<Int>): ByteArray {
        val body = bodyStart(raw) ?: return raw
        val at = body + PLAYER_AUTH_INPUT_INPUT_DATA_OFFSET
        val (flags, end) = readInputData(raw, at) ?: return raw
        val next = (flags + keep).filter { it !in drop }.distinct().sorted()
        if (next.toSet() == flags.toSet()) return raw
        return splice(raw, at, end, writeInputData(next))
    }

    /** Offset of the Nth occurrence of [needle] at or after [from], or -1. */
    fun indexOf(raw: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty() || needle.size > raw.size) return -1
        outer@ for (i in from..raw.size - needle.size) {
            for (j in needle.indices) {
                if (raw[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}