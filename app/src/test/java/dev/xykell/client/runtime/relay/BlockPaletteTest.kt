package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the two block network-id addressing modes.
 *
 * The properties under test: the mode is honoured rather than guessed, FNV-1a
 * matches the published vectors, and a palette never invents a definition for
 * an id it was not given.
 */
class BlockPaletteTest {

    private fun def(id: Int, name: String) =
        BlockPalette.Definition(id, name, "1.26.45")

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `fnv1a32 matches the published 32-bit test vectors`() {
        // Published FNV-1a 32 vectors. If these drift, the hashed mode is wrong.
        assertEquals(0x811c9dc5.toInt(), BlockPalette.fnv1a32(ByteArray(0)))
        assertEquals(0xe40c292c.toInt(), BlockPalette.fnv1a32("a".toByteArray()))
        assertEquals(0xbf9cf968.toInt(), BlockPalette.fnv1a32("foobar".toByteArray()))
    }

    @Test
    fun `fnv1a32 uses the published constants`() {
        assertEquals(0x811c9dc5.toInt(), BlockPalette.FNV1_32_INIT)
        assertEquals(0x01000193, BlockPalette.FNV1_PRIME_32)
    }

    @Test
    fun `no bytes means no hash, not the hash of nothing`() {
        assertNull(BlockPalette.fnv1a32(null))
        assertNull(BlockPalette.hashOf(null))
    }

    @Test
    fun `indexed mode resolves by ordinal`() {
        val p = BlockPalette.indexed(listOf(def(1, "stone"), def(2, "dirt")))
        assertFalse(p.hashed)
        assertEquals("stone", p.resolve(0)?.name)
        assertEquals("dirt", p.resolve(1)?.name)
        assertEquals(2, p.size)
    }

    @Test
    fun `an unknown ordinal is a miss, not a crash`() {
        val p = BlockPalette.indexed(listOf(def(1, "stone")))
        assertNull(p.resolve(9999))
        assertNull(p.resolve(-1))
    }

    @Test
    fun `hashed mode resolves by hash, not by ordinal`() {
        val stoneBytes = bytes(1, 2, 3)
        val dirtBytes = bytes(4, 5, 6)
        val p = BlockPalette.hashed(
            listOf(
                def(1, "stone") to stoneBytes,
                def(2, "dirt") to dirtBytes,
            ),
        )
        assertTrue(p.hashed)
        assertEquals(
            "stone",
            p.resolve(BlockPalette.fnv1a32(stoneBytes)!!)?.name,
        )
        assertEquals("dirt", p.resolve(BlockPalette.fnv1a32(dirtBytes)!!)?.name)
        // Ordinal 0 would be wrong in hashed mode and must not resolve.
        assertNull(p.resolve(0))
    }

    @Test
    fun `an entry with no bytes is kept but unresolvable`() {
        // Hiding it would make an incomplete palette look complete.
        val p = BlockPalette.hashed(listOf(def(1, "stone") to null))
        assertEquals(1, p.size)
        assertEquals(listOf("stone"), p.names())
        assertNull(p.resolve(0))
        assertNull(p.resolve(BlockPalette.fnv1a32(bytes(1))!!))
    }

    @Test
    fun `the first of two identical hashes wins, deterministically`() {
        val same = bytes(9, 9, 9)
        val p = BlockPalette.hashed(
            listOf(def(1, "first") to same, def(2, "second") to same),
        )
        assertEquals(2, p.size)
        assertEquals("first", p.resolve(BlockPalette.fnv1a32(same)!!)?.name)
    }

    @Test
    fun `an empty palette resolves nothing and says so`() {
        val p = BlockPalette.empty()
        assertEquals(0, p.size)
        assertNull(p.resolve(0))
        assertTrue(p.names().isEmpty())
        assertTrue(p.ids().isEmpty())
    }

    @Test
    fun `definition count is capped`() {
        val many = (0 until BlockPalette.MAX_DEFINITIONS + 500).map { def(it, "b$it") }
        val p = BlockPalette.indexed(many)
        assertEquals(BlockPalette.MAX_DEFINITIONS, p.size)
    }

    @Test
    fun `hashed registration is capped too`() {
        val many = (0 until 64).map { def(it, "b$it") to bytes(it, 1) }
        val p = BlockPalette.hashed(many)
        assertEquals(64, p.size)
        assertNotNull(p.resolve(BlockPalette.fnv1a32(bytes(5, 1))!!))
    }

    @Test
    fun `ids and names stay in palette order`() {
        val p = BlockPalette.indexed(listOf(def(7, "a"), def(9, "b"), def(11, "c")))
        assertEquals(listOf("a", "b", "c"), p.names())
        assertEquals(listOf(7, 9, 11), p.ids())
    }
}