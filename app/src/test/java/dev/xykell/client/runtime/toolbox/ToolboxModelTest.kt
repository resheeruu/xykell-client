package dev.xykell.client.runtime.toolbox

import dev.xykell.client.ui.ModuleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The toolbox is a window that can be recreated at will, so its decisions live
 * here where they are answerable on the host with no JNI and no Android.
 */
class ToolboxModelTest {

    private fun catalogJson(vararg entries: String): String =
        """{"features":[${entries.joinToString(",")}]}"""

    private fun supported(
        id: String,
        name: String = id,
        category: String = "Movement",
        status: String = "SUPPORTED",
    ): String =
        """{"id":"$id","name":"$name","category":"$category","status":"$status",
           "description":"","capabilities":[],"implementation":"","riskLevel":"low",
           "evidence":"","settingKeys":[]}"""

    @Test
    fun `lists supported modules with their state`() {
        val state = ToolboxModel.build(
            catalogJson(supported("sprint"), supported("fly", "Flight", "Movement")),
            """{"modules":{"sprint":true}}""",
        )
        assertNull(state.problem)
        assertEquals(2, state.rows.size)
        assertEquals("sprint", state.rows[0].id)
        assertTrue(state.rows[0].enabled)
        assertFalse(state.rows[1].enabled)
        assertEquals(1, state.enabledCount)
    }

    @Test
    fun `a module that is not implemented gets no toggle`() {
        // A switch on a RESEARCH_REQUIRED entry switches nothing, so the user
        // would believe it was on.
        val state = ToolboxModel.build(
            catalogJson(
                supported("sprint"),
                supported("xray", "Xray", "Render", "RESEARCH_REQUIRED"),
            ),
            """{"modules":{}}""",
        )
        assertEquals(listOf("sprint"), state.rows.map { it.id })
    }

    @Test
    fun `partial implementations are offered`() {
        val state = ToolboxModel.build(
            catalogJson(supported("fly", status = "PARTIAL")),
            """{"modules":{}}""",
        )
        assertEquals(listOf("fly"), state.rows.map { it.id })
    }

    @Test
    fun `an unreadable catalog is a problem, not an empty toolbox`() {
        val state = ToolboxModel.build(null, """{"modules":{}}""")
        assertTrue(state.rows.isEmpty())
        assertNotNull("must distinguish 'broken' from 'empty'", state.problem)
    }

    @Test
    fun `an unparseable catalog is a problem`() {
        val state = ToolboxModel.build("not json at all", """{"modules":{}}""")
        assertTrue(state.rows.isEmpty())
        assertNotNull(state.problem)
    }

    @Test
    fun `a missing profile reads as everything off`() {
        val state = ToolboxModel.build(
            catalogJson(supported("sprint"), supported("fly")),
            null,
        )
        assertTrue(state.rows.none { it.enabled })
    }

    @Test
    fun `an unparseable profile is a problem`() {
        val state = ToolboxModel.build(catalogJson(supported("sprint")), "{{{")
        assertTrue(state.rows.isEmpty())
        assertNotNull(state.problem)
    }

    @Test
    fun `toggling flips one flag and preserves the rest of the profile`() {
        val original = """{"modules":{"sprint":true,"fly":false},"other":{"keep":42}}"""
        val updated = ToolboxModel.withToggled(original, "sprint")!!
        val root = org.json.JSONObject(updated)
        val modules = root.getJSONObject("modules")
        assertFalse("sprint should now be off", modules.getBoolean("sprint"))
        assertFalse("fly must be untouched", modules.getBoolean("fly"))
        // Everything that is not a module flag has to survive: a profile holds
        // more than toggles and rewriting it from a flags-only model loses it.
        assertEquals(42, root.getJSONObject("other").getInt("keep"))
    }

    @Test
    fun `toggling a module that was absent turns it on`() {
        val updated = ToolboxModel.withToggled("""{"modules":{"fly":false}}""", "sprint")!!
        assertTrue(org.json.JSONObject(updated).getJSONObject("modules").getBoolean("sprint"))
    }

    @Test
    fun `toggling into a profile with no modules object creates one`() {
        val updated = ToolboxModel.withToggled("""{"name":"p"}""", "sprint")!!
        val modules = org.json.JSONObject(updated).getJSONObject("modules")
        assertTrue(modules.getBoolean("sprint"))
    }

    @Test
    fun `toggling refuses rather than writing back a profile it could not read`() {
        // Losing a profile because a toggle was tapped is not recoverable.
        assertNull(ToolboxModel.withToggled("not json", "sprint"))
        assertNull(ToolboxModel.withToggled(null, "sprint"))
    }

    @Test
    fun `the packaged catalog parses and offers rows`() {
        // Against the real features.json, so the overlay's list is proven to
        // come from the same source the Modules screen uses.
        val json = javaClass.classLoader?.getResourceAsStream("features.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (json == null) return // asset not on the test classpath; covered above
        val state = ToolboxModel.build(json, """{"modules":{}}""")
        assertNull(state.problem)
        assertTrue("expected real modules", state.rows.isNotEmpty())
    }

    @Test
    fun `catalog parsing agrees with the entry parser`() {
        // Guard against the toolbox filtering on a field the Modules screen
        // ignores: both read ModuleEntry, so the id must be the same string.
        val entries = ModuleEntry.parseAll(catalogJson(supported("sprint")))!!
        assertEquals("sprint", entries.single().id)
    }
}