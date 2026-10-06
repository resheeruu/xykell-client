package dev.xykell.client.runtime.capture

/**
 * Repacks an ImageReader row buffer into tightly packed RGBA bytes.
 *
 * The captured plane is padded: rowStride is the distance between the start
 * of consecutive rows and is often larger than width * 4 (driver alignment).
 * Bitmap.copyPixelsFromBuffer assumes packed rows, so the padding has to be
 * removed first. Kept free of android.* types so it runs in the plain JVM
 * test harness.
 */
object PixelPacker {

    /**
     * @param src        plane bytes, at least rowStride * (height - 1) + width * 4 long
     * @param width      capture width in pixels
     * @param height     capture height in pixels
     * @param rowStride  bytes between consecutive rows in [src]
     * @return exactly width * 4 * height packed bytes
     */
    fun repack(src: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        val packedRow = width * 4
        require(rowStride >= packedRow) {
            "rowStride $rowStride smaller than packed row $packedRow"
        }
        require(src.size >= rowStride * (height - 1) + packedRow) {
            "src ${src.size} too small for ${width}x$height at stride $rowStride"
        }

        if (rowStride == packedRow) {
            val out = ByteArray(packedRow * height)
            src.copyInto(out, 0, 0, out.size)
            return out
        }

        val out = ByteArray(packedRow * height)
        for (y in 0 until height) {
            val from = y * rowStride
            src.copyInto(out, y * packedRow, from, from + packedRow)
        }
        return out
    }
}
