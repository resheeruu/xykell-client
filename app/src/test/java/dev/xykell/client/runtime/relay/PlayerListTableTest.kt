package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the online roster decoded from PlayerList 0x3f.
 *
 * Three rules are pinned here:
 *  - entries are keyed on UUID, so a rename replaces rather than duplicates;
 *  - `observe` returns what changed, because a join alert needs to know *who*;
 *  - the decoder never walks past the name, so a struct that grows in a later
 *    version cannot make it read the wrong bytes.
 */
class PlayerListTableTest {

    private fun playerList(type: Int, uuid: ByteArray, name: String?): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0x3f) // header varint
        out.add(type.toByte())
        for (b in uuid) out.add(b)
        if (name != null) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            out.add(bytes.size.toByte())
            for (b in bytes) out.add(b)
        }
        // Trailing fields a real entry carries (xuid, platform chat id, skin
        // data). The decoder must ignore all of it.
        out.add(0)
        out.add(0)
        out.add(0)
        return out.toByteArray()
    }

    private fun uuid(fill: Int): ByteArray = ByteArray(16) { fill.toByte() }

    /** The hex form PlayerListTable keys on. */
    private fun hex(fill: Int): String = String.format("%02x", fill).repeat(16)

    @Test
    fun addReportsWhoJoined() {
        val table = PlayerListTable()
        assertEquals(
            PlayerListTable.Change.Added(hex(1), "Steve"),
            table.observe(playerList(0, uuid(1), "Steve")),
        )
        assertEquals(1, table.size)
        assertEquals(listOf("Steve"), table.names())
    }

    @Test
    fun removeTakesTheEntryOff() {
        val table = PlayerListTable()
        table.observe(playerList(0, uuid(1), "Steve"))
        assertEquals(
            PlayerListTable.Change.Removed(hex(1)),
            table.observe(playerList(1, uuid(1), null)),
        )
        assertEquals(0, table.size)
        assertTrue(table.names().isEmpty())
    }

    @Test
    fun aRenameReplacesRatherThanDuplicates() {
        // The whole reason entries are keyed on uuid: a name-keyed roster would
        // now hold two entries for one person and never remove either.
        val table = PlayerListTable()
        table.observe(playerList(0, uuid(7), "OldName"))
        assertEquals(
            PlayerListTable.Change.Added(hex(7), "NewName"),
            table.observe(playerList(0, uuid(7), "NewName")),
        )
        assertEquals(1, table.size)
        assertEquals(listOf("NewName"), table.names())
    }

    @Test
    fun twoPlayersWithTheSameNameAreTwoEntries() {
        val table = PlayerListTable()
        table.observe(playerList(0, uuid(1), "Steve"))
        table.observe(playerList(0, uuid(2), "Steve"))
        assertEquals(2, table.size)
        table.observe(playerList(1, uuid(1), null))
        assertEquals(1, table.size)
    }

    @Test
    fun clearTypeEmptiesTheRoster() {
        val table = PlayerListTable()
        table.observe(playerList(0, uuid(1), "Steve"))
        table.observe(playerList(0, uuid(2), "Alex"))
        assertEquals(
            PlayerListTable.Change.Cleared,
            table.observe(playerList(2, ByteArray(0), null)),
        )
        assertEquals(0, table.size)
    }

    @Test
    fun truncatedEntryIsIgnoredRatherThanHalfApplied() {
        val table = PlayerListTable()
        // Header + type + five of the sixteen uuid bytes.
        assertNull(table.observe(byteArrayOf(0x3f, 0x00, 1, 2, 3, 4, 5)))
        assertEquals(0, table.size)
    }

    @Test
    fun aNameLongerThanTheBufferIsIgnored() {
        val table = PlayerListTable()
        // Claims 40 bytes of name but supplies three.
        val lying = byteArrayOf(0x3f, 0x00) + ByteArray(16) +
            byteArrayOf(40, 'a'.code.toByte(), 'b'.code.toByte())
        assertNull(table.observe(lying))
        assertEquals(0, table.size)
    }

    @Test
    fun absurdNameLengthIsRefusedWithoutAllocating() {
        val table = PlayerListTable()
        val huge = byteArrayOf(0x3f, 0x00) + ByteArray(16) +
            byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f)
        assertNull(table.observe(huge))
        assertEquals(0, table.size)
    }

    @Test
    fun unknownEntryTypeIsNotApplied() {
        val table = PlayerListTable()
        table.observe(playerList(0, uuid(1), "Steve"))
        assertNull(table.observe(playerList(9, uuid(1), null)))
        assertEquals(1, table.size)
    }

    @Test
    fun rosterIsBoundedAndDropsTheOldest() {
        val table = PlayerListTable(maxPlayers = 4)
        for (i in 1..6) table.observe(playerList(0, uuid(i), "P$i"))
        assertEquals(4, table.size)
        assertTrue(table.names().contains("P6"))
        assertTrue(!table.names().contains("P1"))
    }

    @Test
    fun clearEmptiesEverything() {
        val table = PlayerListTable()
        table.observe(playerList(0, uuid(1), "Steve"))
        table.clear()
        assertEquals(0, table.size)
        assertNull(table.get("nope"))
    }
}