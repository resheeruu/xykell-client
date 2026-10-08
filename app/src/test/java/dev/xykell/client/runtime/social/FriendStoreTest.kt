package dev.xykell.client.runtime.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendStoreTest {

    private fun friend(
        name: String = "Steve",
        color: String = FriendStore.DEFAULT_COLOR,
        notes: String = "",
        server: String = "",
    ) = FriendStore.Friend(name, color, notes, server)

    @Test
    fun addAcceptsValidFriend() {
        val store = FriendStore()
        assertTrue(store.add(friend(notes = "mining buddy")) is FriendStore.Result.Ok)
        assertEquals(1, store.size())
        assertTrue(store.isFriend("Steve"))
    }

    @Test
    fun addRejectsEmptyAndOversizeNames() {
        val store = FriendStore()
        assertTrue(store.add(friend(name = "")) is FriendStore.Result.Invalid)
        assertTrue(store.add(friend(name = "x".repeat(33))) is FriendStore.Result.Invalid)
        assertTrue(store.add(friend(name = "x".repeat(32))) is FriendStore.Result.Ok)
    }

    @Test
    fun addRejectsDuplicateExactName() {
        val store = FriendStore()
        store.add(friend(name = "Steve"))
        val dup = store.add(friend(name = "Steve"))
        assertTrue(dup is FriendStore.Result.Invalid)
        // Native semantics: match is exact, not case-insensitive.
        assertTrue(store.add(friend(name = "steve")) is FriendStore.Result.Ok)
        assertEquals(2, store.size())
    }

    @Test
    fun addRejectsBadColors() {
        val store = FriendStore()
        for (bad in listOf("", "4FD8C7", "#4FD8C", "#4FD8C77", "4FD8C777", "#GGGGGG", "#4FD8C7 ")) {
            assertTrue("expected reject for '$bad'", store.add(friend(color = bad)) is FriendStore.Result.Invalid)
        }
        assertTrue(store.add(friend(name = "Red", color = "#ff0000")) is FriendStore.Result.Ok)
        assertTrue(store.add(friend(name = "Blue", color = "#ABCDEF")) is FriendStore.Result.Ok)
    }

    @Test
    fun addRejectsOversizeNotesOrServer() {
        val store = FriendStore()
        assertTrue(store.add(friend(notes = "n".repeat(FriendStore.MAX_NOTES + 1))) is FriendStore.Result.Invalid)
        assertTrue(store.add(friend(server = "s".repeat(FriendStore.MAX_SERVER + 1))) is FriendStore.Result.Invalid)
    }

    @Test
    fun addRejectsWhenFull() {
        val store = FriendStore()
        for (i in 0 until FriendStore.MAX_FRIENDS) {
            assertTrue(store.add(friend(name = "f$i")) is FriendStore.Result.Ok)
        }
        assertTrue(store.add(friend(name = "one-more")) is FriendStore.Result.Invalid)
    }

    @Test
    fun removeDeletesOnlyNamedFriend() {
        val store = FriendStore()
        store.add(friend(name = "Steve"))
        store.add(friend(name = "Alex"))
        assertTrue(store.remove("Steve"))
        assertFalse(store.isFriend("Steve"))
        assertFalse(store.remove("Steve"))
        assertTrue(store.isFriend("Alex"))
    }

    @Test
    fun renameMovesEntryAndKeepsFields() {
        val store = FriendStore()
        store.add(friend(name = "Steve", notes = "n1", server = "mc.example"))
        assertTrue(store.rename("Steve", "Alex") is FriendStore.Result.Ok)
        assertFalse(store.isFriend("Steve"))
        val moved = store.all().single()
        assertEquals("Alex", moved.name)
        assertEquals("n1", moved.notes)
        assertEquals("mc.example", moved.server)
    }

    @Test
    fun renameRejectsTakenAndMissingNames() {
        val store = FriendStore()
        store.add(friend(name = "Steve"))
        store.add(friend(name = "Alex"))
        assertTrue(store.rename("Steve", "Alex") is FriendStore.Result.Invalid)
        assertTrue(store.rename("Nobody", "Herobrine") is FriendStore.Result.Invalid)
        assertTrue(store.rename("Steve", "") is FriendStore.Result.Invalid)
        assertEquals(2, store.size())
    }

    @Test
    fun setColorOnlyTouchesNamedFriend() {
        val store = FriendStore()
        store.add(friend(name = "Steve"))
        store.add(friend(name = "Alex"))
        assertTrue(store.setColor("Steve", "#FF0000") is FriendStore.Result.Ok)
        assertEquals("#FF0000", store.all().first { it.name == "Steve" }.color)
        assertEquals(FriendStore.DEFAULT_COLOR, store.all().first { it.name == "Alex" }.color)
        assertTrue(store.setColor("Steve", "red") is FriendStore.Result.Invalid)
        assertTrue(store.setColor("Nobody", "#FF0000") is FriendStore.Result.Invalid)
    }

    @Test
    fun serializeRoundTripsWholeBook() {
        val store = FriendStore()
        store.add(friend(name = "Steve", notes = "n", server = "mc.example"))
        store.add(friend(name = "Alex", color = "#FF0000"))
        val json = store.serialize()

        val restored = FriendStore()
        assertTrue(restored.deserialize(json) is FriendStore.Result.Ok)
        assertEquals(2, restored.size())
        assertEquals(store.sorted(), restored.sorted())
        assertTrue(json.contains("\"schemaVersion\":1"))
    }

    @Test
    fun deserializeBlankClearsBook() {
        val store = FriendStore()
        store.add(friend())
        assertTrue(store.deserialize("") is FriendStore.Result.Ok)
        assertEquals(0, store.size())
    }

    @Test
    fun deserializeRefusesMalformedDocumentsWholesale() {
        val store = FriendStore()
        store.add(friend(name = "KeepMe"))
        for (bad in listOf("{", "[]", "{\"friends\":{}}", "{\"friends\":[{\"color\":\"#4FD8C7\"}]}",
                "{\"friends\":[{\"name\":\"A\",\"color\":\"bad\"}]}",
                "{\"friends\":[{\"name\":\"A\"},{\"name\":\"A\"}]}",
                "{\"friends\":[{\"name\":\"ok\"}],\"schemaVersion\":99}")) {
            assertTrue("expected reject for $bad", store.deserialize(bad) is FriendStore.Result.Invalid)
            assertEquals("previous book kept for $bad", 1, store.size())
            assertTrue(store.isFriend("KeepMe"))
        }
    }

    @Test
    fun sortedIsCaseInsensitiveByName() {
        val store = FriendStore()
        store.add(friend(name = "zeus"))
        store.add(friend(name = "Alex"))
        store.add(friend(name = "steve"))
        assertEquals(listOf("Alex", "steve", "zeus"), store.sorted().map { it.name })
    }
}
