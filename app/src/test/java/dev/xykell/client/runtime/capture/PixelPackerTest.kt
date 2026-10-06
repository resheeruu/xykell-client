package dev.xykell.client.runtime.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The repack is the only bit of capture logic that can run off-device, and it
 * is exactly the bit that silently corrupts screenshots when wrong: a missed
 * padding row shifts every following row.
 */
class PixelPackerTest {

    /** width x height bytes where byte value = row * 16 + (col % 16). */
    private fun plane(width: Int, height: Int, rowStride: Int): ByteArray {
        val packedRow = width * 4
        val src = ByteArray(rowStride * height) { -1 } // -1 marks untouched padding
        for (y in 0 until height) {
            for (x in 0 until packedRow) {
                src[y * rowStride + x] = ((y * 16 + (x % 16)) and 0xFF).toByte()
            }
        }
        return src
    }

    @Test
    fun `packed stride is copied through unchanged`() {
        val width = 3
        val height = 4
        val stride = width * 4
        val src = plane(width, height, stride)

        val out = PixelPacker.repack(src, width, height, stride)

        assertEquals(stride * height, out.size)
        assertArrayEquals(src.copyOf(out.size), out)
    }

    @Test
    fun `padded stride drops the padding column of every row`() {
        val width = 3
        val height = 5
        val packedRow = width * 4
        val stride = packedRow + 12 // driver-aligned padding
        val src = plane(width, height, stride)

        val out = PixelPacker.repack(src, width, height, stride)

        assertEquals(packedRow * height, out.size)
        for (y in 0 until height) {
            for (b in 0 until packedRow) {
                val expected = ((y * 16 + (b % 16)) and 0xFF).toByte()
                assertEquals(
                    "row $y byte $b",
                    expected,
                    out[y * packedRow + b],
                )
            }
        }
    }

    @Test
    fun `single row with padding still copies exactly packedRow bytes`() {
        val width = 2
        val stride = width * 4 + 8
        val src = plane(width, 1, stride)

        val out = PixelPacker.repack(src, width, 1, stride)

        assertEquals(width * 4, out.size)
        for (b in 0 until width * 4) {
            assertEquals((b % 16).toByte(), out[b])
        }
    }

    @Test
    fun `rejects stride smaller than packed row`() {
        assertThrows(IllegalArgumentException::class.java) {
            PixelPacker.repack(ByteArray(64), 4, 1, 8)
        }
    }

    @Test
    fun `rejects source shorter than the last row needs`() {
        assertThrows(IllegalArgumentException::class.java) {
            PixelPacker.repack(ByteArray(40), 4, 2, 32)
        }
    }
}
