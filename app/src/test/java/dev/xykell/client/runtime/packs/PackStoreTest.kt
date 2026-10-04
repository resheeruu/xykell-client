package dev.xykell.client.runtime.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class PackStoreTest {
    private fun entry(
        name: String = "P",
        type: PackType = PackType.RESOURCE,
        version: String = "1.0.0",
        author: String = "Author",
        enabled: Boolean = false
    ) = PackEntry(
        id = "id-$name", name = name, type = type, version = version, author = author, enabled = enabled
    )

    @Test
    fun validEntryPasses() {
        assertEquals(null, PackStore.validate("My Pack", PackType.RESOURCE))
        assertEquals(null, PackStore.validate("p".repeat(64), PackType.BEHAVIOR))
    }

    @Test
    fun invalidNameRejected() {
        assertEquals("empty name", PackStore.validate("  ", PackType.RESOURCE))
        assertEquals("empty name", PackStore.validate("", PackType.RESOURCE))
        assertEquals("name over 64 chars", PackStore.validate("x".repeat(65), PackType.RESOURCE))
    }

    @Test
    fun jsonRoundTrip() {
        val entries = listOf(
            entry(name = "Alpha", type = PackType.RESOURCE, enabled = true),
            entry(name = "Beta", type = PackType.BEHAVIOR, author = "Me")
        )
        val decoded = PackStore.fromJson(PackStore.toJson(entries))
        assertEquals(2, decoded.size)
        assertEquals("Alpha", decoded[0].name)
        assertEquals(PackType.RESOURCE, decoded[0].type)
        assertEquals(true, decoded[0].enabled)
        assertEquals("Beta", decoded[1].name)
        assertEquals(PackType.BEHAVIOR, decoded[1].type)
        assertEquals("Me", decoded[1].author)
    }

    @Test
    fun corruptJsonReturnsEmpty() {
        assertTrue(PackStore.fromJson("").isEmpty())
        assertTrue(PackStore.fromJson("not json").isEmpty())
        assertTrue(PackStore.fromJson("{\"packs\": \"nope\"}").isEmpty())
    }

    @Test
    fun corruptEntriesDropped() {
        val json = org.json.JSONObject().apply {
            put("version", 1)
            put("packs", org.json.JSONArray().apply {
                put(org.json.JSONObject().put("name", "Good"))
                put(org.json.JSONObject().put("name", ""))
                put(org.json.JSONObject().put("name", "Bad").put("author", "x".repeat(65)))
                put(org.json.JSONObject().put("author", "No name"))
            })
        }.toString()
        val decoded = PackStore.fromJson(json)
        // "Good" valid, "Bad" valid (author truncated), "" and no-name dropped
        assertEquals(2, decoded.size)
        assertEquals(listOf("Good", "Bad"), decoded.map { it.name })
        assertEquals(64, decoded[1].author.length)
    }

    @Test
    fun unknownFieldsIgnoredDefaultsApplied() {
        val json = """{"packs":[{"name":"Old","futureField":{"x":1}}]}"""
        val decoded = PackStore.fromJson(json)
        assertEquals(1, decoded.size)
        assertEquals(false, decoded[0].enabled)
        assertEquals("", decoded[0].author)
        assertEquals("", decoded[0].version)
        assertEquals(PackType.UNKNOWN, decoded[0].type)
    }

    @Test
    fun sortEnabledThenName() {
        val a = entry(name = "Alpha", enabled = false)
        val b = entry(name = "Beta", enabled = true)
        val c = entry(name = "Gamma", enabled = false)
        val d = entry(name = "delta", enabled = false)
        val sorted = PackStore.sort(listOf(a, b, c, d))
        assertEquals(listOf("Beta", "Alpha", "delta", "Gamma"), sorted.map { it.name })
    }

    @Test
    fun filterMatchesNameAuthorVersion() {
        val a = entry(name = "Texture Pack", author = "Steve", version = "1.21.0")
        val b = entry(name = "Behavior Pack", author = "Alex", version = "1.20.0")
        val all = listOf(a, b)
        assertEquals(2, PackStore.filter(all, "").size)
        assertEquals(1, PackStore.filter(all, "texture").size)
        assertEquals(1, PackStore.filter(all, "steve").size)
        assertEquals(1, PackStore.filter(all, "1.21").size)
        assertEquals(0, PackStore.filter(all, "zzz").size)
    }

    @Test
    fun manifestExtractResource() {
        val manifest = org.json.JSONObject().apply {
            put("header", org.json.JSONObject().apply {
                put("name", "Test Pack")
                put("description", "A resource pack")
                put("version", "1.0.0")
                put("author", "Tester")
            })
            put("modules", org.json.JSONArray().put(
                org.json.JSONObject().put("type", "resources").put("version", "1.0.0")
            ))
        }.toString().toByteArray()
        val meta = PackStore.extractFromManifest(manifest)
        assertEquals("Test Pack", meta["name"])
        assertEquals("A resource pack", meta["description"])
        assertEquals("1.0.0", meta["version"])
        assertEquals("Tester", meta["author"])
        assertEquals(PackType.RESOURCE, meta["packType"])
    }

    @Test
    fun manifestExtractBehavior() {
        val manifest = org.json.JSONObject().apply {
            put("header", org.json.JSONObject().apply {
                put("name", "Behavior Pack")
            })
            put("modules", org.json.JSONArray().put(
                org.json.JSONObject().put("type", "data")
            ))
        }.toString().toByteArray()
        val meta = PackStore.extractFromManifest(manifest)
        assertEquals(PackType.BEHAVIOR, meta["packType"])
    }

    @Test
    fun manifestExtractMixedPrefersResource() {
        val manifest = org.json.JSONObject().apply {
            put("header", org.json.JSONObject().put("name", "Mixed"))
            put("modules", org.json.JSONArray().apply {
                put(org.json.JSONObject().put("type", "data"))
                put(org.json.JSONObject().put("type", "resources"))
            })
        }.toString().toByteArray()
        val meta = PackStore.extractFromManifest(manifest)
        assertEquals(PackType.RESOURCE, meta["packType"])
    }

    @Test
    fun manifestExtractUnknownType() {
        val manifest = org.json.JSONObject().apply {
            put("header", org.json.JSONObject().put("name", "Script Pack"))
            put("modules", org.json.JSONArray().put(
                org.json.JSONObject().put("type", "script")
            ))
        }.toString().toByteArray()
        val meta = PackStore.extractFromManifest(manifest)
        assertEquals(PackType.UNKNOWN, meta["packType"])
    }

    @Test
    fun manifestCorruptReturnsEmpty() {
        assertTrue(PackStore.extractFromManifest("not json".toByteArray()).isEmpty())
        assertTrue(PackStore.extractFromManifest("{}".toByteArray()).isEmpty())
    }
}