package dev.xykell.client.runtime.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Host JVM tests for `.mcpack` container scanning.
 *
 * A pack arrives from outside the app, so the two properties that matter are
 * that a decompression bomb is refused with the total it reached rather than
 * being read into the heap, and that a traversal entry name is *reported*
 * instead of being silently accepted.
 */
class PackArchiveTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val manifest = """
        {"format_version":1,"header":{"name":"Aurora","description":"d","version":[1,0,0],
         "author":"me"},"modules":[{"type":"resources","uuid":"x","version":[1,0,0]}]}
    """.trimIndent().toByteArray()

    @Test
    fun `a well-formed pack reads and yields its manifest`() {
        val c = PackArchive.scan(
            zip("manifest.json" to manifest, "resources/pack_icon.png" to byteArrayOf(1, 2, 3)),
        )
        assertTrue(c.readable)
        assertFalse(c.truncated)
        assertFalse(c.hasTraversal)
        assertEquals(2, c.entryCount)
        assertEquals("Aurora", c.manifest["name"])
        assertEquals(PackType.RESOURCE, c.manifest["packType"])
        assertTrue(PackArchive.isUsablePack(c))
    }

    @Test
    fun `names under a prefix are selectable`() {
        val c = PackArchive.scan(
            zip(
                "manifest.json" to manifest,
                "resources/a.png" to byteArrayOf(1),
                "resources/b.png" to byteArrayOf(2),
                "textures/c.png" to byteArrayOf(3),
            ),
        )
        assertEquals(
            listOf("resources/a.png", "resources/b.png"),
            PackArchive.namesUnder(c, "resources/"),
        )
    }

    @Test
    fun `a file that is not a zip is reported, not thrown`() {
        val c = PackArchive.scan("this is not a zip at all".toByteArray())
        assertFalse(c.readable)
        assertEquals(0, c.entryCount)
        assertFalse(PackArchive.isUsablePack(c))
    }

    @Test
    fun `null and empty input are refused quietly`() {
        assertFalse(PackArchive.scan(null).readable)
        assertFalse(PackArchive.scan(ByteArray(0)).readable)
    }

    @Test
    fun `a zip with no manifest is readable but not usable`() {
        val c = PackArchive.scan(zip("resources/a.png" to byteArrayOf(1)))
        assertTrue(c.readable)
        assertTrue(c.manifest.isEmpty())
        assertFalse(PackArchive.isUsablePack(c))
    }

    @Test
    fun `a traversal entry name is reported`() {
        val c = PackArchive.scan(
            zip("manifest.json" to manifest, "../../databases/app.db" to byteArrayOf(1)),
        )
        assertTrue("zip-slip must be surfaced", c.hasTraversal)
    }

    @Test
    fun `traversal detection covers the usual shapes`() {
        assertTrue(PackArchive.hasTraversalName("../x"))
        assertTrue(PackArchive.hasTraversalName("a/../../b"))
        assertTrue(PackArchive.hasTraversalName("/etc/passwd"))
        assertTrue(PackArchive.hasTraversalName("C:/Windows/x"))
        assertTrue(PackArchive.hasTraversalName("a\\..\\b"))
        assertFalse(PackArchive.hasTraversalName("resources/a.png"))
        assertFalse(PackArchive.hasTraversalName("a..b/c"))
        assertFalse(PackArchive.hasTraversalName(""))
    }

    @Test
    fun `directory members cost no bytes`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("resources/"))
            z.closeEntry()
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(manifest)
            z.closeEntry()
        }
        val c = PackArchive.scan(out.toByteArray())
        assertTrue(c.readable)
        assertEquals(2, c.entryCount)
        assertEquals(manifest.size.toLong(), c.totalBytes)
    }

    @Test
    fun `entry count is capped`() {
        // Cap is 4096; build a container with more entries than that.
        val entries = ArrayList<Pair<String, ByteArray>>()
        repeat(PackArchive.MAX_ENTRIES + 50) { entries.add("f$it.bin" to ByteArray(0)) }
        val c = PackArchive.scan(zip(*entries.toTypedArray()))
        assertTrue(c.readable)
        assertTrue("must stop at the cap", c.entryCount <= PackArchive.MAX_ENTRIES)
        assertTrue(c.truncated)
    }

    @Test
    fun `a total larger than the cap stops the walk`() {
        // One entry that individually fits but blows the total: the per-packet
        // style ceiling, which per-entry checks alone would miss.
        val big = 32 * 1024 * 1024
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            repeat(8) { i ->
                z.putNextEntry(ZipEntry("big$i.bin"))
                z.write(ByteArray(big))
                z.closeEntry()
            }
        }
        val c = PackArchive.scan(out.toByteArray())
        assertTrue("total cap must stop the walk", c.truncated)
        assertTrue(c.totalBytes <= PackArchive.MAX_TOTAL_BYTES.toLong() + big)
    }

    @Test
    fun `a manifest nested in a directory is still found`() {
        val c = PackArchive.scan(
            zip("pack/manifest.json" to manifest, "pack/resources/a.png" to byteArrayOf(1)),
        )
        assertEquals("Aurora", c.manifest["name"])
    }

    @Test
    fun `an oversized manifest is not parsed as a manifest`() {
        val huge = ByteArray(PackArchive.MAX_MANIFEST_BYTES + 1) { 'x'.code.toByte() }
        val c = PackArchive.scan(zip("manifest.json" to huge))
        assertTrue(c.readable)
        assertTrue(c.manifest.isEmpty())
    }

    @Test
    fun `the empty contents value is not usable`() {
        assertFalse(PackArchive.isUsablePack(PackArchive.Contents.EMPTY))
    }
}