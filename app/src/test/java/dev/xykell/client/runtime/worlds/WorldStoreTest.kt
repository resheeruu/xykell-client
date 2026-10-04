package dev.xykell.client.runtime.worlds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldStoreTest {
    private fun entry(
        name: String = "W",
        notes: String = "",
        favorite: Boolean = false,
        lastUsed: Long = 0L,
        gameVersion: String = ""
    ) = WorldEntry(
        id = "id-$name", name = name, notes = notes, favorite = favorite, lastUsed = lastUsed, gameVersion = gameVersion
    )

    @Test
    fun validNamePasses() {
        assertEquals(null, WorldStore.validate("My World"))
        assertEquals(null, WorldStore.validate("w".repeat(64)))
    }

    @Test
    fun invalidNameRejected() {
        assertEquals("empty name", WorldStore.validate("  "))
        assertEquals("empty name", WorldStore.validate(""))
        assertEquals("name over 64 chars", WorldStore.validate("x".repeat(65)))
    }

    @Test
    fun jsonRoundTrip() {
        val entries = listOf(
            entry(name = "Alpha", favorite = true, lastUsed = 42L, notes = "survival"),
            entry(name = "Beta", gameVersion = "1.21.0")
        )
        val decoded = WorldStore.fromJson(WorldStore.toJson(entries))
        assertEquals(2, decoded.size)
        assertEquals("Alpha", decoded[0].name)
        assertEquals(true, decoded[0].favorite)
        assertEquals(42L, decoded[0].lastUsed)
        assertEquals("survival", decoded[0].notes)
        assertEquals("Beta", decoded[1].name)
        assertEquals("1.21.0", decoded[1].gameVersion)
    }

    @Test
    fun corruptJsonReturnsEmpty() {
        assertTrue(WorldStore.fromJson("").isEmpty())
        assertTrue(WorldStore.fromJson("not json").isEmpty())
        assertTrue(WorldStore.fromJson("{\"worlds\": \"nope\"}").isEmpty())
    }

@Test
    fun corruptEntriesDropped() {
        val json = org.json.JSONObject().apply {
            put("version", 1)
            put("worlds", org.json.JSONArray().apply {
                put(org.json.JSONObject().put("name", "Good"))
                put(org.json.JSONObject().put("name", ""))
                put(org.json.JSONObject().put("name", "Bad").put("notes", "x".repeat(501)))
                put(org.json.JSONObject().put("notes", "No name"))
            })
        }.toString()
        val decoded = WorldStore.fromJson(json)
        // "Good" valid, "Bad" valid (notes truncated to 500), "" and no-name dropped
        assertEquals(2, decoded.size)
        assertEquals(listOf("Good", "Bad"), decoded.map { it.name })
        assertEquals(500, decoded[1].notes.length)
    }

    @Test
    fun unknownFieldsIgnoredDefaultsApplied() {
        val json = """{"worlds":[{"name":"Old","futureField":{"x":1}}]}"""
        val decoded = WorldStore.fromJson(json)
        assertEquals(1, decoded.size)
        assertEquals(false, decoded[0].favorite)
        assertEquals("", decoded[0].notes)
        assertEquals(0L, decoded[0].lastUsed)
        assertEquals("", decoded[0].gameVersion)
        assertEquals("", decoded[0].worldPath)
    }

    @Test
    fun sortFavoritesThenRecencyThenName() {
        val a = entry(name = "Alpha", favorite = false, lastUsed = 10L)
        val b = entry(name = "Beta", favorite = true, lastUsed = 1L)
        val c = entry(name = "Gamma", favorite = false, lastUsed = 10L)
        val d = entry(name = "delta", favorite = false, lastUsed = 10L)
        val sorted = WorldStore.sort(listOf(a, b, c, d))
        assertEquals(listOf("Beta", "Alpha", "delta", "Gamma"), sorted.map { it.name })
    }

    @Test
    fun filterMatchesNameNotesVersion() {
        val a = entry(name = "Survival World", notes = "hardcore", gameVersion = "1.21.0")
        val b = entry(name = "Creative", notes = "building", gameVersion = "1.20.0")
        val all = listOf(a, b)
        assertEquals(2, WorldStore.filter(all, "").size)
        assertEquals(1, WorldStore.filter(all, "survival").size)
        assertEquals(1, WorldStore.filter(all, "hardcore").size)
        assertEquals(1, WorldStore.filter(all, "1.21").size)
        assertEquals(1, WorldStore.filter(all, "building").size)
        assertEquals(0, WorldStore.filter(all, "zzz").size)
    }
}