package dev.xykell.client.runtime.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class ServerStoreTest {
    private fun entry(
        name: String = "S",
        address: String = "play.example.net",
        port: Int = ServerStore.DEFAULT_PORT,
        favorite: Boolean = false,
        lastUsed: Long = 0L,
        notes: String = ""
    ) = ServerEntry(
        id = "id-$name", name = name, address = address, port = port,
        favorite = favorite, lastUsed = lastUsed, notes = notes
    )

    @Test
    fun validEntryPasses() {
        assertEquals(null, ServerStore.validate("Arena", "play.example.net", 19132))
        assertEquals(null, ServerStore.validate("Arena", "192.168.1.10", 25565))
        assertEquals(null, ServerStore.validate("Arena", "2001:db8::1", 19132))
    }

    @Test
    fun invalidNamesRejected() {
        assertEquals("empty name", ServerStore.validate("  ", "play.example.net", 19132))
        assertEquals("empty name", ServerStore.validate("", "play.example.net", 19132))
        assertEquals("name over 64 chars", ServerStore.validate("x".repeat(65), "a.b", 19132))
    }

    @Test
    fun invalidAddressesRejected() {
        assertEquals("empty address", ServerStore.validate("A", "", 19132))
        assertEquals("address must be host or IP only",
            ServerStore.validate("A", "https://play.example.net", 19132))
        assertEquals("address must be host or IP only",
            ServerStore.validate("A", "play.example.net/x", 19132))
        assertEquals("address must be host or IP only",
            ServerStore.validate("A", "user@play.example.net", 19132))
        assertEquals("address must be host or IP only",
            ServerStore.validate("A", "play example.net", 19132))
        assertEquals("address has invalid characters",
            ServerStore.validate("A", "play.example.net!", 19132))
    }

    @Test
    fun invalidPortsRejected() {
        assertEquals("port must be 1-65535", ServerStore.validate("A", "a.b", 0))
        assertEquals("port must be 1-65535", ServerStore.validate("A", "a.b", 65536))
        assertEquals("port must be 1-65535", ServerStore.validate("A", "a.b", -1))
    }

    @Test
    fun probeOpen() {
        val socket = ServerSocket(0)
        try {
            assertEquals(ServerStore.ProbeResult.OPEN,
                ServerStore.probe("127.0.0.1", socket.localPort, 2000))
        } finally {
            socket.close()
        }
    }

    @Test
    fun probeClosed() {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        assertEquals(ServerStore.ProbeResult.CLOSED,
            ServerStore.probe("127.0.0.1", port, 2000))
    }

    @Test
    fun probeInvalid() {
        assertEquals(ServerStore.ProbeResult.INVALID, ServerStore.probe("", 19132, 500))
        assertEquals(ServerStore.ProbeResult.INVALID, ServerStore.probe("a.b", 0, 500))
    }

    @Test
    fun probeBlackholeIsNotOpen() {
        // TEST-NET-1 (RFC 5737) is non-routable: connect must not
        // report OPEN regardless of how the network answers.
        assertNotEquals(ServerStore.ProbeResult.OPEN,
            ServerStore.probe("192.0.2.1", 19132, 250))
    }

    @Test
    fun jsonRoundTrip() {
        val entries = listOf(
            entry(name = "A", favorite = true, lastUsed = 42L),
            entry(name = "B", address = "10.0.0.5", port = 25565, notes = "pvp")
        )
        val decoded = ServerStore.fromJson(ServerStore.toJson(entries))
        assertEquals(2, decoded.size)
        assertEquals("A", decoded[0].name)
        assertEquals(true, decoded[0].favorite)
        assertEquals(42L, decoded[0].lastUsed)
        assertEquals("10.0.0.5", decoded[1].address)
        assertEquals(25565, decoded[1].port)
        assertEquals("pvp", decoded[1].notes)
        assertEquals("B", decoded[1].name)
        assertEquals("10.0.0.5:25565", decoded[1].displayAddress)
        assertEquals("play.example.net", decoded[0].displayAddress)
    }

    @Test
    fun displayAddressHidesDefaultPort() {
        assertEquals("play.example.net", entry().displayAddress)
        assertEquals("mc.example.com:25565", entry(address = "mc.example.com", port = 25565).displayAddress)
    }

    @Test
    fun corruptJsonReturnsEmpty() {
        assertTrue(ServerStore.fromJson("").isEmpty())
        assertTrue(ServerStore.fromJson("not json").isEmpty())
        assertTrue(ServerStore.fromJson("{\"servers\": \"nope\"}").isEmpty())
    }

    @Test
    fun corruptEntriesDropped() {
        val json = """{"version":1,"servers":[
            {"name":"Good","address":"a.b","port":19132},
            {"name":"","address":"a.b","port":19132},
            {"name":"Bad port","address":"a.b","port":99999},
            {"name":"No addr","address":"","port":19132}
        ]}"""
        val decoded = ServerStore.fromJson(json)
        assertEquals(1, decoded.size)
        assertEquals("Good", decoded[0].name)
    }

    @Test
    fun unknownFieldsIgnoredAndDefaultsApplied() {
        val json = """{"servers":[{"name":"Old","address":"a.b","port":19132,
            "futureField":{"x":1}}]}"""
        val decoded = ServerStore.fromJson(json)
        assertEquals(1, decoded.size)
        assertEquals(false, decoded[0].favorite)
        assertEquals("", decoded[0].notes)
        assertEquals(0L, decoded[0].lastUsed)
    }

    @Test
    fun sortFavoritesThenRecencyThenName() {
        val a = entry(name = "Alpha", favorite = false, lastUsed = 10L)
        val b = entry(name = "Beta", favorite = true, lastUsed = 1L)
        val c = entry(name = "Gamma", favorite = false, lastUsed = 10L)
        val d = entry(name = "delta", favorite = false, lastUsed = 10L)
        val sorted = ServerStore.sort(listOf(a, b, c, d))
        // Favorites first, then lastUsed desc, then name
        // case-insensitive: alpha < delta < gamma.
        assertEquals(listOf("Beta", "Alpha", "delta", "Gamma"), sorted.map { it.name })
    }

    @Test
    fun filterMatchesNameAddressNotes() {
        val a = entry(name = "PvP Arena", address = "pvp.example.net", notes = "no rules")
        val b = entry(name = "Survival", address = "smp.example.net", notes = "chill")
        val all = listOf(a, b)
        assertEquals(2, ServerStore.filter(all, "").size)
        assertEquals(1, ServerStore.filter(all, "pvp").size) // one entry (name+address both match)
        assertEquals(2, ServerStore.filter(all, "EXAMPLE.NET").size) // both entries
        assertEquals(1, ServerStore.filter(all, "no rules").size)
        assertEquals(1, ServerStore.filter(all, "chill").size)
        assertEquals(0, ServerStore.filter(all, "zzz").size)
    }
}
