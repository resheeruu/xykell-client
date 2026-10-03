package dev.xykell.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host JVM tests for settings row mapping (Batch 7). Pure logic, no
 * Android framework. Sliders appear ONLY for ranged numerics.
 */
class SettingRowMapperTest {

    @Test
    fun rowKinds() {
        assertEquals(SettingRowMapper.RowKind.TOGGLE, SettingRowMapper.rowKind("bool", 0.0, 0.0, 0))
        assertEquals(SettingRowMapper.RowKind.SLIDER_INT, SettingRowMapper.rowKind("int", 0.0, 100.0, 0))
        assertEquals(
            SettingRowMapper.RowKind.SLIDER_DOUBLE,
            SettingRowMapper.rowKind("double", 0.5, 3.0, 0),
        )
        assertEquals(SettingRowMapper.RowKind.DROPDOWN, SettingRowMapper.rowKind("choice", 0.0, 0.0, 3))
        assertEquals(SettingRowMapper.RowKind.TEXT, SettingRowMapper.rowKind("text", 0.0, 0.0, 0))
        // Degenerate ranges fall back to TEXT, never a broken slider.
        assertEquals(SettingRowMapper.RowKind.TEXT, SettingRowMapper.rowKind("int", 5.0, 5.0, 0))
        assertEquals(SettingRowMapper.RowKind.TEXT, SettingRowMapper.rowKind("choice", 0.0, 0.0, 1))
        assertEquals(SettingRowMapper.RowKind.TEXT, SettingRowMapper.rowKind("bogus", 0.0, 0.0, 0))
    }

    @Test
    fun sectionOrder() {
        assertEquals(
            listOf("General", "HUD", "Privacy", "Zzz"),
            SettingRowMapper.sectionOrder(listOf("Privacy", "HUD", "General", "Zzz")),
        )
    }

    @Test
    fun displaySections() {
        assertEquals("General", SettingRowMapper.displaySection("client"))
        assertEquals("General", SettingRowMapper.displaySection("profile"))
        assertEquals("HUD", SettingRowMapper.displaySection("hud"))
        assertEquals("Notifications", SettingRowMapper.displaySection("notifications"))
        assertEquals("Privacy", SettingRowMapper.displaySection("privacy"))
        assertEquals("Advanced", SettingRowMapper.displaySection("other"))
    }
}
