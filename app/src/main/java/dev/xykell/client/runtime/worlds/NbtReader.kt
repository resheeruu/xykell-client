package dev.xykell.client.runtime.worlds

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/** Minimal gzipped NBT reader for Bedrock level.dat.
 *  Supports TAG_Compound (0x0A) with nested TAG_String (0x08),
 *  TAG_Int (0x03), TAG_Long (0x04), TAG_Byte_Array (0x07).
 *  Reads only the fields Xykell needs: LevelName, Generator, GameType,
 *  Difficulty, LastPlayed, SizeOnDisk, Version. */
object NbtReader {

    private const val TAG_END = 0x00
    private const val TAG_BYTE = 0x01
    private const val TAG_SHORT = 0x02
    private const val TAG_INT = 0x03
    private const val TAG_LONG = 0x04
    private const val TAG_FLOAT = 0x05
    private const val TAG_DOUBLE = 0x06
    private const val TAG_BYTE_ARRAY = 0x07
    private const val TAG_STRING = 0x08
    private const val TAG_LIST = 0x09
    private const val TAG_COMPOUND = 0x0A
    private const val TAG_INT_ARRAY = 0x0B
    private const val TAG_LONG_ARRAY = 0x0C

    /** Parse level.dat from file. Returns map of known keys or empty map on failure. */
    fun parseLevelDat(file: File): Map<String, Any> {
        return try {
            FileInputStream(file).use { fis ->
                GZIPInputStream(fis).use { gis ->
                    readCompressedStream(gis)
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Parse raw gzipped NBT bytes. */
    fun parseLevelDat(bytes: ByteArray): Map<String, Any> {
        return try {
            ByteArrayInputStream(bytes).use { bis ->
                GZIPInputStream(bis).use { gis ->
                    readCompressedStream(gis)
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun readCompressedStream(gis: GZIPInputStream): Map<String, Any> {
        val dis = DataInputStream(gis)
        // First byte is root tag type (should be TAG_COMPOUND)
        val rootType = dis.readByte().toInt()
        if (rootType != TAG_COMPOUND) return emptyMap()
        // Root name (empty string for root)
        val rootName = readString(dis)
        if (!rootName.isEmpty()) return emptyMap()
        // Parse compound
        return readCompound(dis) as Map<String, Any>
    }

    private fun readCompound(dis: DataInputStream): MutableMap<String, Any> {
        val map = mutableMapOf<String, Any>()
        while (true) {
            val tagType = dis.readByte().toInt()
            if (tagType == TAG_END) break
            val name = readString(dis)
            val value = readTag(tagType, dis)
            if (value != null) map[name] = value
        }
        return map
    }

    private fun readString(dis: DataInputStream): String {
        val length = dis.readShort().toInt()
        if (length < 0) return ""
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = dis.read(bytes, read, length - read)
            if (n < 0) throw EOFException()
            read += n
        }
        return String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    }

    private fun readTag(tagType: Int, dis: DataInputStream): Any? {
        return when (tagType) {
            TAG_BYTE -> dis.readByte()
            TAG_SHORT -> dis.readShort()
            TAG_INT -> dis.readInt()
            TAG_LONG -> dis.readLong()
            TAG_FLOAT -> dis.readFloat()
            TAG_DOUBLE -> dis.readDouble()
            TAG_BYTE_ARRAY -> {
                val length = dis.readInt()
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = dis.read(bytes, read, length - read)
                    if (n < 0) throw EOFException()
                    read += n
                }
                bytes
            }
            TAG_STRING -> readString(dis)
            TAG_LIST -> {
                val elementType = dis.readByte().toInt()
                val length = dis.readInt()
                val list = mutableListOf<Any>()
                repeat(length) {
                    val v = readTag(elementType, dis)
                    if (v != null) list += v
                }
                list
            }
            TAG_COMPOUND -> readCompound(dis)
            TAG_INT_ARRAY -> {
                val length = dis.readInt()
                val arr = IntArray(length)
                for (i in 0 until length) arr[i] = dis.readInt()
                arr
            }
            TAG_LONG_ARRAY -> {
                val length = dis.readInt()
                val arr = LongArray(length)
                for (i in 0 until length) arr[i] = dis.readLong()
                arr
            }
            else -> {
                // Skip unknown tag
                skipTag(tagType, dis)
                null
            }
        }
    }

    private fun skipTag(tagType: Int, dis: DataInputStream) {
        when (tagType) {
            TAG_BYTE -> dis.skipBytes(1)
            TAG_SHORT -> dis.skipBytes(2)
            TAG_INT -> dis.skipBytes(4)
            TAG_LONG -> dis.skipBytes(8)
            TAG_FLOAT -> dis.skipBytes(4)
            TAG_DOUBLE -> dis.skipBytes(8)
            TAG_BYTE_ARRAY -> {
                val len = dis.readInt()
                dis.skipBytes(len)
            }
            TAG_STRING -> {
                val len = dis.readShort().toInt()
                dis.skipBytes(len)
            }
            TAG_LIST -> {
                val ignored = dis.readByte()
                val len = dis.readInt()
                // Cannot skip easily without knowing element type sizes
                // For robustness, throw — but Bedrock level.dat has no nested lists in root
                throw IOException("TAG_LIST skip not implemented")
            }
            TAG_COMPOUND -> {
                while (true) {
                    val t = dis.readByte().toInt()
                    if (t == TAG_END) break
                    val ignored = readString(dis) // name
                    skipTag(t, dis)
                }
            }
            TAG_INT_ARRAY -> {
                val len = dis.readInt()
                dis.skipBytes(len * 4)
            }
            TAG_LONG_ARRAY -> {
                val len = dis.readInt()
                dis.skipBytes(len * 8)
            }
            else -> {}
        }
    }
}