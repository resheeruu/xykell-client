package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host tests for the death log decoded from DeathInfo 0xbd.
 *
 * The rules pinned here: the packet is only believed when it actually carries a
 * cause, the death position is the player's own last reported one, and the log
 * is bounded.
 */
class DeathTableTest {

    private fun deathInfo(cause: String): ByteArray {
        val out = ArrayList<Byte>()
        // 0xbd has its high bit set, so its varuint header is two bytes.
        out.add(0xbd.toByte())
        out.add(0x01.toByte())
        val bytes = cause.toByteArray(Charsets.UTF_8)
        out.add(bytes.size.toByte())
        for (b in bytes) out.add(b)
        // The trailing string array: empty is a valid packet.
        out.add(0)
        return out.toByteArray()
    }

    @Test
    fun aCauseIsRecorded() {
        val table = DeathTable()
        val death = table.observe(deathInfo("fell from a high place"), 1f, 64f, 2f, 5_000L)
        assertNotNull(death)
        assertEquals("fell from a high place", death!!.cause)
        assertEquals(1f, death.x, 0f)
        assertEquals(64f, death.y, 0f)
        assertEquals(5_000L, death.observedAtMs)
        assertEquals(1, table.size)
    }

    @Test
    fun thePositionIsTheOnesOwnLastReported() {
        // The death notice carries no coordinates, so these are exactly what
        // were handed in: a server-supplied position would be a lie about where
        // the player was standing.
        val table = DeathTable()
        val death = table.observe(deathInfo("hit"), -12.5f, 70f, 33.25f, 1L)
        assertEquals(-12.5f, death!!.x, 0f)
        assertEquals(70f, death.y, 0f)
        assertEquals(33.25f, death.z, 0f)
    }

    @Test
    fun anEmptyCauseIsNotADeath() {
        // It would render as a death with no reason, which reads as a bug in
        // the client rather than something that happened in the world.
        val table = DeathTable()
        assertNull(table.observe(deathInfo(""), 0f, 0f, 0f, 0L))
        assertEquals(0, table.size)
    }

    @Test
    fun anotherPacketIsNotADeath() {
        val table = DeathTable()
        assertNull(table.observe(byteArrayOf(0x13, 1, 2, 3), 0f, 0f, 0f, 0L))
        assertEquals(0, table.size)
    }

    @Test
    fun aTruncatedCauseIsRefused() {
        val table = DeathTable()
        // Claims 30 bytes of cause, supplies two.
        assertNull(table.observe(byteArrayOf(0xbd.toByte(), 0x01, 30.toByte(), 'a'.code.toByte(), 'b'.code.toByte()), 0f, 0f, 0f, 0L))
    }

    @Test
    fun latestIsTheMostRecentAndRecentIsNewestFirst() {
        val table = DeathTable()
        table.observe(deathInfo("first"), 1f, 1f, 1f, 1L)
        table.observe(deathInfo("second"), 2f, 2f, 2f, 2L)
        assertEquals("second", table.latest()!!.cause)
        assertEquals(listOf("second", "first"), table.recent().map { it.cause })
    }

    @Test
    fun theLogIsBounded() {
        val table = DeathTable(maxDeaths = 3)
        for (i in 1..5) table.observe(deathInfo("d$i"), 0f, 0f, 0f, i.toLong())
        assertEquals(3, table.size)
        assertEquals("d5", table.latest()!!.cause)
        assertTrue(!table.recent().any { it.cause == "d1" })
    }

    @Test
    fun thereIsNoLatestBeforeAnyDeath() {
        assertNull(DeathTable().latest())
    }

    private fun assertTrue(v: Boolean) = org.junit.Assert.assertTrue(v)
}