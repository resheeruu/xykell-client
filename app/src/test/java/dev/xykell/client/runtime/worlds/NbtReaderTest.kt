package dev.xykell.client.runtime.worlds

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NbtReader behaviour, focused on the paths real level.dat files take:
 *  known fields, unknown/invalid type bytes, nested TAG_List structures,
 *  and corrupt list headers failing closed. */
class NbtReaderTest {

    /** Builds a gzipped level.dat root compound from the given entries.
     *  Each triple is (type byte, name, payload writer). */
    private fun levelDat(entries: Array<Triple<Int, String, (DataOutputStream) -> Unit>>): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            DataOutputStream(gz).use { out ->
                out.writeByte(0x0A) // root TAG_COMPOUND
                out.writeShort(0) // root name
                for ((type, name, payload) in entries) {
                    out.writeByte(type)
                    out.writeShort(name.length)
                    out.writeBytes(name)
                    payload(out)
                }
                out.writeByte(0x00) // TAG_END
            }
        }
        return bos.toByteArray()
    }

    private fun stringPayload(v: String) = { out: DataOutputStream ->
        out.writeShort(v.length)
        out.writeBytes(v)
    }
    private fun intPayload(v: Int) = { out: DataOutputStream -> out.writeInt(v) }

    @Test
    fun knownFieldsParse() {
        val bytes = levelDat(
            arrayOf(
                Triple(0x08, "LevelName", stringPayload("My World")),
                Triple(0x03, "GameType", intPayload(1)),
            )
        )
        val map = NbtReader.parseLevelDat(bytes)
        assertEquals("My World", map["LevelName"])
        assertEquals(1, map["GameType"])
    }

    @Test
    fun nonStringIntLongTagsParse() {
        val bytes = levelDat(
            arrayOf(
                Triple(0x04, "LastPlayed", { out: DataOutputStream -> out.writeLong(123456789L) }),
                Triple(0x01, "Difficulty", { out: DataOutputStream -> out.writeByte(2) }),
                Triple(0x08, "LevelName", stringPayload("W")),
            )
        )
        val map = NbtReader.parseLevelDat(bytes)
        assertEquals(123456789L, map["LastPlayed"])
        assertEquals("W", map["LevelName"])
    }

    @Test
    fun invalidTagTypeFailsClosed() {
        // 0x0D is not a valid NBT type: the parse must fail closed
        // (empty map) instead of desyncing into fabricated values.
        val bytes = levelDat(
            arrayOf(
                Triple(0x0D, "Bogus", { out: DataOutputStream -> out.writeInt(0) }),
                Triple(0x08, "LevelName", stringPayload("W")),
            )
        )
        assertEquals(emptyMap<String, Any>(), NbtReader.parseLevelDat(bytes))
    }

    @Test
    fun nestedTagListsParse() {
        // Unknown-domain field: a TAG_List of compounds, each holding a
        // nested TAG_List of ints. Must parse without throwing; the known
        // field after it must survive.
        val listPayload = { out: DataOutputStream ->
            out.writeByte(0x0A) // element type: TAG_COMPOUND
            out.writeInt(2) // two compounds
            repeat(2) {
                out.writeByte(0x09) // nested TAG_LIST inside the compound
                out.writeShort(0) // name
                out.writeByte(0x03) // list of ints
                out.writeInt(3)
                out.writeInt(11)
                out.writeInt(22)
                out.writeInt(33)
                out.writeByte(0x00) // end compound
            }
        }
        val bytes = levelDat(
            arrayOf(
                Triple(0x09, "SpawnRules", listPayload),
                Triple(0x08, "LevelName", stringPayload("Nested")),
            )
        )
        val map = NbtReader.parseLevelDat(bytes)
        assertEquals("Nested", map["LevelName"])
        @Suppress("UNCHECKED_CAST")
        val spawn = map["SpawnRules"] as? List<Any>
        assertEquals(2, spawn?.size)
        val first = spawn?.get(0) as? Map<*, *>
        assertEquals(3, (first?.get("") as? List<*>)?.size)
    }

    @Test
    fun emptyTagListParses() {
        val emptyList = { out: DataOutputStream ->
            out.writeByte(0x08) // element type: TAG_STRING
            out.writeInt(0) // no elements
        }
        val bytes = levelDat(
            arrayOf(
                Triple(0x09, "Empty", emptyList),
                Triple(0x03, "Generator", intPayload(2)),
            )
        )
        val map = NbtReader.parseLevelDat(bytes)
        assertEquals(2, map["Generator"])
        assertTrue(map.containsKey("Empty"))
    }

    @Test
    fun corruptListLengthFailsClosed() {
        // Negative length in a TAG_List header must fail closed
        // (empty map), never hang or fabricate an empty list.
        val corrupt = { out: DataOutputStream ->
            out.writeByte(0x08) // element type
            out.writeInt(-1) // corrupt length
        }
        val bytes = levelDat(arrayOf(Triple(0x09, "Bad", corrupt)))
        assertEquals(emptyMap<String, Any>(), NbtReader.parseLevelDat(bytes))
    }

    @Test
    fun listWithEndElementsFailsClosed() {
        // length > 0 with element type TAG_END is an invalid header.
        val corrupt = { out: DataOutputStream ->
            out.writeByte(0x00)
            out.writeInt(5)
        }
        val bytes = levelDat(arrayOf(Triple(0x09, "Bad", corrupt)))
        assertEquals(emptyMap<String, Any>(), NbtReader.parseLevelDat(bytes))
    }
}
