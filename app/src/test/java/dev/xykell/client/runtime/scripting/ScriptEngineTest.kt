package dev.xykell.client.runtime.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptEngineTest {

    @Test
    fun parseRules_empty() {
        val json = """{"rules": []}"""
        val rules = ScriptEngine.parseRules(json)!!
        assertTrue(rules.isEmpty())
    }

    @Test
    fun parseRules_valid() {
        val json = """{
            "rules": [{
                "id": "test1",
                "name": "Test Rule",
                "enabled": true,
                "triggers": [{"type": "on_startup"}],
                "conditions": [{"type": "profile_equals", "profileName": "Default"}],
                "actions": [{"type": "log", "message": "Hello"}]
            }]
        }"""
        val rules = ScriptEngine.parseRules(json)!!
        assertEquals(1, rules.size)
        assertEquals("test1", rules[0].id)
        assertEquals("Test Rule", rules[0].name)
        assertTrue(rules[0].enabled)
        assertEquals(1, rules[0].triggers.size)
        assertEquals(1, rules[0].conditions.size)
        assertEquals(1, rules[0].actions.size)
    }

    @Test
    fun parseRules_invalidJson() {
        val rules = ScriptEngine.parseRules("not json")
        assertNull(rules)
    }

    @Test
    fun parseRules_missingFields() {
        val json = """{"rules": [{}]}"""
        val rules = ScriptEngine.parseRules(json)!!
        assertTrue(rules.isEmpty())
    }

    @Test
    fun evaluateConditions_empty() {
        val ctx = ScriptEngine.EvalContext(
            moduleEnabled = { false },
            currentProfile = "Default",
            currentTheme = "Xykell Dark",
            getSetting = { _, _ -> null }
        )
        assertTrue(ScriptEngine.evaluateConditions(emptyList(), ctx))
    }

    @Test
    fun evaluateConditions_moduleEnabled() {
        val ctx = ScriptEngine.EvalContext(
            moduleEnabled = { it == "xykell.hud.fps" },
            currentProfile = "Default",
            currentTheme = "Xykell Dark",
            getSetting = { _, _ -> null }
        )
        val cond = ScriptEngine.Condition.ModuleEnabled("xykell.hud.fps")
        assertTrue(ScriptEngine.evaluateConditions(listOf(cond), ctx))
        val cond2 = ScriptEngine.Condition.ModuleEnabled("xykell.hud.cps")
        assertFalse(ScriptEngine.evaluateConditions(listOf(cond2), ctx))
    }

    @Test
    fun evaluateConditions_profileEquals() {
        val ctx = ScriptEngine.EvalContext(
            moduleEnabled = { false },
            currentProfile = "Default",
            currentTheme = "Xykell Dark",
            getSetting = { _, _ -> null }
        )
        val cond = ScriptEngine.Condition.ProfileEquals("Default")
        assertTrue(ScriptEngine.evaluateConditions(listOf(cond), ctx))
        val cond2 = ScriptEngine.Condition.ProfileEquals("Performance")
        assertFalse(ScriptEngine.evaluateConditions(listOf(cond2), ctx))
    }

    @Test
    fun evaluateConditions_themeEquals() {
        val ctx = ScriptEngine.EvalContext(
            moduleEnabled = { false },
            currentProfile = "Default",
            currentTheme = "Xykell Dark",
            getSetting = { _, _ -> null }
        )
        val cond = ScriptEngine.Condition.ThemeEquals("Xykell Dark")
        assertTrue(ScriptEngine.evaluateConditions(listOf(cond), ctx))
    }

    @Test
    fun evaluateConditions_settingEquals() {
        val ctx = ScriptEngine.EvalContext(
            moduleEnabled = { false },
            currentProfile = "Default",
            currentTheme = "Xykell Dark",
            getSetting = { s, k -> if (s == "client" && k == "theme") "Xykell Dark" else null }
        )
        val cond = ScriptEngine.Condition.SettingEquals("client", "theme", "Xykell Dark")
        assertTrue(ScriptEngine.evaluateConditions(listOf(cond), ctx))
    }

    @Test
    fun evaluateConditions_timeBetween() {
        val ctx = ScriptEngine.EvalContext(
            moduleEnabled = { false },
            currentProfile = "Default",
            currentTheme = "Xykell Dark",
            getSetting = { _, _ -> null }
        )
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val cond = ScriptEngine.Condition.TimeBetween(hour, hour)
        assertTrue(ScriptEngine.evaluateConditions(listOf(cond), ctx))
        val cond2 = ScriptEngine.Condition.TimeBetween((hour + 1) % 24, (hour + 2) % 24)
        assertFalse(ScriptEngine.evaluateConditions(listOf(cond2), ctx))
    }

    @Test
    fun evaluateConditions_and() {
        val ctx = ScriptEngine.EvalContext(
            moduleEnabled = { it == "xykell.hud.fps" },
            currentProfile = "Default",
            currentTheme = "Xykell Dark",
            getSetting = { _, _ -> null }
        )
        val cond = ScriptEngine.Condition.And(listOf(
            ScriptEngine.Condition.ModuleEnabled("xykell.hud.fps"),
            ScriptEngine.Condition.ProfileEquals("Default")
        ))
        assertTrue(ScriptEngine.evaluateConditions(listOf(cond), ctx))
    }

    @Test
    fun evaluateConditions_or() {
        val ctx = ScriptEngine.EvalContext(
            moduleEnabled = { false },
            currentProfile = "Default",
            currentTheme = "Xykell Dark",
            getSetting = { _, _ -> null }
        )
        val cond = ScriptEngine.Condition.Or(listOf(
            ScriptEngine.Condition.ModuleEnabled("xykell.hud.fps"),
            ScriptEngine.Condition.ProfileEquals("Default")
        ))
        assertTrue(ScriptEngine.evaluateConditions(listOf(cond), ctx))
    }

    @Test
    fun evaluateConditions_not() {
        val ctx = ScriptEngine.EvalContext(
            moduleEnabled = { false },
            currentProfile = "Default",
            currentTheme = "Xykell Dark",
            getSetting = { _, _ -> null }
        )
        val cond = ScriptEngine.Condition.Not(ScriptEngine.Condition.ModuleEnabled("xykell.hud.fps"))
        assertTrue(ScriptEngine.evaluateConditions(listOf(cond), ctx))
    }

    @Test
    fun serializeRules() {
        val rules = listOf(
            ScriptEngine.Rule(
                id = "test1",
                name = "Test",
                enabled = true,
                triggers = listOf(ScriptEngine.Trigger.OnStartup()),
                conditions = listOf(ScriptEngine.Condition.ProfileEquals("Default")),
                actions = listOf(ScriptEngine.Action.Log("Hello"))
            )
        )
        val json = ScriptEngine.serializeRules(rules)
        val parsed = ScriptEngine.parseRules(json)!!
        assertEquals(1, parsed.size)
        assertEquals("test1", parsed[0].id)
    }

    @Test
    fun parseRules_handlesAllTriggerTypes() {
        val json = """{
            "rules": [{
                "id": "t1",
                "name": "Test",
                "enabled": true,
                "triggers": [
                    {"type": "on_module_toggle", "moduleId": "m1"},
                    {"type": "on_profile_change"},
                    {"type": "on_theme_change"},
                    {"type": "on_setting_change", "section": "s", "key": "k"},
                    {"type": "on_startup"},
                    {"type": "on_interval", "milliseconds": 1000},
                    {"type": "on_hud_element_change", "elementId": "e1"}
                ],
                "conditions": [],
                "actions": []
            }]
        }"""
        val rules = ScriptEngine.parseRules(json)!!
        assertEquals(1, rules.size)
        assertEquals(7, rules[0].triggers.size)
    }

    @Test
    fun parseRules_handlesAllConditionTypes() {
        val json = """{
            "rules": [{
                "id": "c1",
                "name": "Test",
                "enabled": true,
                "triggers": [{"type": "on_startup"}],
                "conditions": [
                    {"type": "module_enabled", "moduleId": "m1"},
                    {"type": "profile_equals", "profileName": "p1"},
                    {"type": "theme_equals", "themeName": "t1"},
                    {"type": "setting_equals", "section": "s", "key": "k", "value": "v"},
                    {"type": "time_between", "startHour": 8, "endHour": 18},
                    {"type": "and", "conditions": []},
                    {"type": "or", "conditions": []},
                    {"type": "not", "condition": {"type": "module_enabled", "moduleId": "m2"}}
                ],
                "actions": []
            }]
        }"""
        val rules = ScriptEngine.parseRules(json)!!
        assertEquals(1, rules.size)
        assertEquals(8, rules[0].conditions.size)
    }

    @Test
    fun parseRules_handlesAllActionTypes() {
        val json = """{
            "rules": [{
                "id": "a1",
                "name": "Test",
                "enabled": true,
                "triggers": [{"type": "on_startup"}],
                "conditions": [],
                "actions": [
                    {"type": "set_module_enabled", "moduleId": "m1", "enabled": true},
                    {"type": "set_profile", "profileName": "p1"},
                    {"type": "set_theme", "themeName": "t1"},
                    {"type": "set_setting", "section": "s", "key": "k", "value": "v"},
                    {"type": "set_hud_element_visible", "elementId": "e1", "visible": true},
                    {"type": "set_hud_element_position", "elementId": "e1", "x": 10, "y": 20},
                    {"type": "set_hud_element_scale", "elementId": "e1", "scale": 1.5},
                    {"type": "log", "message": "test"},
                    {"type": "notify", "title": "T", "message": "M"}
                ]
            }]
        }"""
        val rules = ScriptEngine.parseRules(json)!!
        assertEquals(1, rules.size)
        assertEquals(9, rules[0].actions.size)
    }
}