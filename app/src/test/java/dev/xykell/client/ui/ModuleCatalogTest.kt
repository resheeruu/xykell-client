package dev.xykell.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the module catalog parser/grouping/search (Batch 12).
 * Pure logic against the real registry shape; malformed input must not
 * throw and must not fabricate entries.
 */
class ModuleCatalogTest {

    private val fixture = """
        {"features": [
          {"id": "xykell.client.core", "name": "Core", "category": "CLIENT",
           "description": "M1 proven core", "status": "PARTIAL",
           "capabilities": ["NATIVE"], "implementation": "native",
           "risk_level": "LOW", "evidence": "M1 + host test_core",
           "settings": [{"key": "enabled", "type": "bool"},
                        {"key": "fps_cap", "type": "int"}]},
          {"id": "xykell.hud.fps", "name": "FPS Counter", "category": "HUD",
           "description": "on-screen FPS", "status": "PARTIAL",
           "capabilities": ["RENDER"], "implementation": "native",
           "settings": [{"key": "enabled"}]},
          {"id": "xykell.combat.aura", "name": "Combat Aura",
           "category": "COMBAT", "description": "research needed",
           "status": "RESEARCH_REQUIRED", "capabilities": ["HYBRID"]},
          {"id": "", "name": "broken", "category": "MISC",
           "status": "PARTIAL"}
        ]}
    """.trimIndent()

    @Test
    fun parsesRequiredFieldsAndSkipsInvalid() {
        val entries = ModuleCatalog.parseAll(fixture)
        assertNotNull(entries)
        assertEquals(3, entries!!.size) // id-less entry skipped
        val core = entries.first()
        assertEquals("xykell.client.core", core.id)
        assertEquals("CLIENT", core.category)
        assertEquals("PARTIAL", core.status)
        assertEquals("M1 proven core", core.description)
        assertEquals(listOf("NATIVE"), core.capabilities)
        assertEquals(listOf("enabled", "fps_cap"), core.settingKeys)
        assertEquals("native", core.implementation)
        assertEquals("LOW", core.riskLevel)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(ModuleCatalog.parseAll("not json"))
        assertNull(ModuleCatalog.parseAll("[1,2]"))
        assertNull(ModuleCatalog.parseAll(""))
    }

    @Test
    fun missingOptionalFieldsDefaultGracefully() {
        val entries = ModuleCatalog.parseAll(fixture)!!
        val aura = entries.first { it.id == "xykell.combat.aura" }
        assertEquals("", aura.implementation)
        assertEquals("", aura.evidence)
        assertEquals("", aura.settingKeys.joinToString())
        assertTrue(aura.capabilities.contains("HYBRID"))
    }

    @Test
    fun preferenceSwitchPolicy() {
        val entries = ModuleCatalog.parseAll(fixture)!!
        assertTrue(entries.first { it.id == "xykell.client.core" }.supportsPreference)
        assertFalse(entries.first { it.id == "xykell.combat.aura" }.supportsPreference)
        // Forward-compat: SUPPORTED and NOT_IMPLEMENTED spellings.
        assertTrue(ModuleEntry(
            "x", "x", "MISC", "SUPPORTED", "", emptyList(), "", "", "", emptyList(),
        ).supportsPreference)
        assertFalse(ModuleEntry(
            "y", "y", "MISC", "NOT_IMPLEMENTED", "", emptyList(), "", "", "", emptyList(),
        ).supportsPreference)
    }

    @Test
    fun searchMatchesAcrossFields() {
        val entries = ModuleCatalog.parseAll(fixture)!!
        assertEquals(entries, ModuleCatalog.search(entries, "  "))  // blank = all
        assertEquals(1, ModuleCatalog.search(entries, "aura").size)
        assertEquals(1, ModuleCatalog.search(entries, "fps").size)
        // Status is deliberately not a search field — filtering by
        // "partial" matches nothing, it is not a name/id/desc/category.
        assertEquals(0, ModuleCatalog.search(entries, "partial").size)
        assertEquals(1, ModuleCatalog.search(entries, "m1 proven").size)
        assertEquals(1, ModuleCatalog.search(entries, "combat").size)
        assertEquals(0, ModuleCatalog.search(entries, "zzz").size)
    }

    @Test
    fun groupPreservesFirstSeenOrder() {
        val entries = ModuleCatalog.parseAll(fixture)!!
        val groups = ModuleCatalog.groupByCategory(entries)
        assertEquals(listOf("CLIENT", "HUD", "COMBAT"), groups.map { it.first })
        assertEquals(1, groups[0].second.size)
        // Re-grouping a filtered subset keeps relative order too.
        val subset = entries.filter { it.supportsPreference }
        val g2 = ModuleCatalog.groupByCategory(subset)
        assertEquals(listOf("CLIENT", "HUD"), g2.map { it.first })
    }
}
