package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the block-palette producer.
 *
 * The property throughout: a palette we cannot read fully produces **no**
 * palette. A partial palette resolves later ids to the wrong definition with no
 * error at all, which is the failure this whole type exists to prevent.
 */
class BlockPaletteReaderTest {

    private fun varUInt(v: Int): ByteArray {
        val out = ArrayList<Byte>()
        var x = v
        while (true) {
            val b = (x and 0x7f)
            x = x ushr 7
            out.add((if (x == 0) b else b or 0x80).toByte())
            if (x == 0) break
        }
        return out.toByteArray()
    }

    private fun payload(version: Int, count: Int, tail: ByteArray = ByteArray(0)): ByteArray {
        val head = varUInt(version)
        val n = varUInt(count)
        return ByteArray(head.size + n.size + tail.size).also { out ->
            System.arraycopy(head, 0, out, 0, head.size)
            System.arraycopy(n, 0, out, head.size, n.size)
            System.arraycopy(tail, 0, out, head.size + n.size, tail.size)
        }
    }

    private fun source(n: Int) = BlockPaletteReader.DefinitionSource { index ->
        if (index < 0 || index >= n) {
            null
        } else {
            BlockPaletteReader.Entry(
                BlockPalette.Definition(index, "block_$index", "1.26.45"),
                byteArrayOf(index.toByte(), 0x5a),
            )
        }
    }

    @Test
    fun `an indexed palette resolves by ordinal`() {
        val r = BlockPaletteReader.read(payload(1, 3), hashed = false, source = source(3))
        assertTrue(r is BlockPaletteReader.Result.Ok)
        val palette = (r as BlockPaletteReader.Result.Ok).palette
        assertTrue(!palette.hashed)
        assertEquals(3, palette.size)
        assertEquals("block_0", palette.resolve(0)?.name)
        assertEquals("block_2", palette.resolve(2)?.name)
    }

    @Test
    fun `a hashed palette resolves by hash and not by ordinal`() {
        val r = BlockPaletteReader.read(payload(1, 3), hashed = true, source = source(3))
        assertTrue(r is BlockPaletteReader.Result.Ok)
        val palette = (r as BlockPaletteReader.Result.Ok).palette
        assertTrue(palette.hashed)
        val bytes = byteArrayOf(1, 0x5a)
        assertEquals("block_1", palette.resolve(BlockPalette.fnv1a32(bytes)!!)?.name)
        // Ordinal 0 would be wrong in hashed mode and must not resolve.
        assertNull(palette.resolve(0))
    }

    @Test
    fun `an empty payload is refused`() {
        val r = BlockPaletteReader.read(ByteArray(0), hashed = false, source = source(1))
        assertEquals(
            BlockPaletteReader.Refusal.EMPTY_PAYLOAD,
            (r as BlockPaletteReader.Result.Refused).reason,
        )
    }

    @Test
    fun `an unknown palette version is refused rather than parsed hopefully`() {
        for (v in listOf(0, 2, 99)) {
            val r = BlockPaletteReader.read(payload(v, 1), hashed = false, source = source(1))
            assertEquals(
                "version $v must refuse",
                BlockPaletteReader.Refusal.UNSUPPORTED_VERSION,
                (r as BlockPaletteReader.Result.Refused).reason,
            )
        }
    }

    @Test
    fun `a truncated envelope is refused`() {
        // Only the version byte; no count follows.
        val r = BlockPaletteReader.read(byteArrayOf(1), hashed = false, source = source(1))
        assertEquals(
            BlockPaletteReader.Refusal.TRUNCATED_ENVELOPE,
            (r as BlockPaletteReader.Result.Refused).reason,
        )
    }

    @Test
    fun `a varint that never terminates is refused rather than spinning`() {
        val r = BlockPaletteReader.read(
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            hashed = false,
            source = source(1),
        )
        assertTrue(r is BlockPaletteReader.Result.Refused)
    }

    @Test
    fun `a zero count is refused`() {
        val r = BlockPaletteReader.read(payload(1, 0), hashed = false, source = source(1))
        assertEquals(
            BlockPaletteReader.Refusal.COUNT_OUT_OF_RANGE,
            (r as BlockPaletteReader.Result.Refused).reason,
        )
    }

    @Test
    fun `a count past the cap is refused`() {
        // The envelope claims far more entries than the cap allows, so it is
        // refused before any allocation is sized from it.
        val r = BlockPaletteReader.read(
            payload(1, BlockPalette.MAX_DEFINITIONS + 1),
            hashed = false,
            source = source(1),
        )
        assertEquals(
            BlockPaletteReader.Refusal.COUNT_OUT_OF_RANGE,
            (r as BlockPaletteReader.Result.Refused).reason,
        )
    }

    @Test
    fun `a source that runs short yields no palette at all`() {
        // Envelope claims 5 entries; the source can only produce 2. Producing a
        // 2-entry palette would resolve ids 2..4 to the wrong blocks.
        val r = BlockPaletteReader.read(payload(1, 5), hashed = false, source = source(2))
        assertEquals(
            BlockPaletteReader.Refusal.TOO_FEW_ENTRIES,
            (r as BlockPaletteReader.Result.Refused).reason,
        )
    }

    @Test
    fun `an encoder that throws is a refusal, not a hole`() {
        val r = BlockPaletteReader.read(payload(1, 2), hashed = false, source = {
            if (it == 1) throw IllegalStateException("bad entry") else source(2).entry(it)
        })
        assertEquals(
            BlockPaletteReader.Refusal.ENCODER_FAILED,
            (r as BlockPaletteReader.Result.Refused).reason,
        )
    }

    @Test
    fun `a custom entry cap is honoured`() {
        val r = BlockPaletteReader.read(
            payload(1, 4), hashed = false, source = source(4), maxEntries = 3,
        )
        assertEquals(
            BlockPaletteReader.Refusal.COUNT_OUT_OF_RANGE,
            (r as BlockPaletteReader.Result.Refused).reason,
        )
    }

    @Test
    fun `fromEntries builds both modes`() {
        val entries = (0 until 2).map {
            BlockPaletteReader.Entry(
                BlockPalette.Definition(it, "b$it", "1"),
                byteArrayOf(it.toByte()),
            )
        }
        val idx = BlockPaletteReader.fromEntries(entries, hashed = false)
        assertEquals("b1", ((idx as BlockPaletteReader.Result.Ok).palette.resolve(1))?.name)

        val hashed = BlockPaletteReader.fromEntries(entries, hashed = true)
        val p = (hashed as BlockPaletteReader.Result.Ok).palette
        assertEquals("b1", p.resolve(BlockPalette.fnv1a32(byteArrayOf(1))!!)?.name)
    }

    @Test
    fun `fromEntries refuses empty and oversized`() {
        assertTrue(BlockPaletteReader.fromEntries(emptyList(), false) is BlockPaletteReader.Result.Refused)
        val many = (0 until 5).map {
            BlockPaletteReader.Entry(BlockPalette.Definition(it, "b$it", "1"), null)
        }
        assertTrue(
            BlockPaletteReader.fromEntries(many, false, maxEntries = 3)
                is BlockPaletteReader.Result.Refused,
        )
    }

    @Test
    fun `supported versions are declared, not inferred`() {
        assertEquals(setOf(1), BlockPaletteReader.SUPPORTED_VERSIONS)
    }

    @Test
    fun `entries with no encoded bytes are kept but unresolvable in hashed mode`() {
        val entries = listOf(
            BlockPaletteReader.Entry(BlockPalette.Definition(0, "a", "1"), null),
        )
        val p = (BlockPaletteReader.fromEntries(entries, hashed = true)
            as BlockPaletteReader.Result.Ok).palette
        assertEquals(1, p.size)
        assertEquals(listOf("a"), p.names())
        assertNull(p.resolve(0))
    }
}