package dev.xykell.client.runtime.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptEngineTest {

    private fun codes(e: List<ScriptEngine.ValidationError>) = e.map { it.code }

    private fun ctx(
        modules: Map<String, Boolean> = emptyMap(),
        profile: String = "Default",
        theme: String = "Xykell Dark",
        settings: Map<String, String> = emptyMap(),
        hour: Int = 12,
    ) = ScriptEngine.EvalContext(
        moduleEnabled = { modules[it] ?: false },
        currentProfile = profile,
        currentTheme = theme,
        getSetting = { s, k -> settings["$s/$k"] },
        currentHour = hour,
    )

    // --- Parsing

    @Test
    fun parseEmptyDocumentYieldsNoRulesAndNoErrors() {
        val d = ScriptEngine.parseDocument("")
        assertTrue(d.ok)
        assertTrue(d.rules.isEmpty())
    }

    @Test
    fun parseValidRule() {
        val d = ScriptEngine.parseDocument(
            """{"rules":[{"id":"a","name":"A","enabled":true,
               "triggers":[{"type":"on_startup"}],
               "conditions":[{"type":"theme_equals","themeName":"Xykell Dark"}],
               "actions":[{"type":"log","message":"hi"}]}]}""",
        )
        assertTrue(d.ok)
        assertEquals(1, d.rules.size)
        assertEquals("a", d.rules[0].id)
    }

    @Test
    fun malformedJsonIsReportedNotThrown() {
        val d = ScriptEngine.parseDocument("{not json")
        assertFalse(d.ok)
        assertEquals(listOf("malformed_json"), codes(d.errors))
    }

    @Test
    fun missingRequiredFieldsReportedPerRule() {
        val d = ScriptEngine.parseDocument("""{"rules":[{"id":"","name":""}]}""")
        assertFalse(d.ok)
        assertTrue(d.rules.isEmpty())
    }

    @Test
    fun unknownJsonFieldsAreTolerated() {
        val d = ScriptEngine.parseDocument(
            """{"rules":[{"id":"a","name":"A","author":"someone","triggers":[{"type":"on_startup",
               "future":1}],"actions":[{"type":"log","message":"m","extra":true}]}]}""",
        )
        assertTrue(d.ok)
        assertEquals(1, d.rules.size)
    }

    @Test
    fun ruleWithNoTriggersIsRejected() {
        val d = ScriptEngine.parseDocument(
            """{"rules":[{"id":"a","name":"A","triggers":[],"actions":[]}]}""",
        )
        assertTrue(d.rules.isEmpty())
    }

    @Test
    fun tooManyRulesRefusesWholeDocument() {
        val rules = (0 until ScriptEngine.MAX_RULES + 1).joinToString(",") {
            """{"id":"r$it","name":"R","triggers":[{"type":"on_startup"}],
               "actions":[{"type":"log","message":"m"}]}"""
        }
        val d = ScriptEngine.parseDocument("""{"rules":[$rules]}""")
        assertFalse(d.ok)
        assertTrue(codes(d.errors).contains("too_many_rules"))
        assertTrue(d.rules.isEmpty())
    }

    @Test
    fun duplicateRuleIdsReportedAndFirstKept() {
        val d = ScriptEngine.parseDocument(
            """{"rules":[
              {"id":"dup","name":"A","triggers":[{"type":"on_startup"}],
               "actions":[{"type":"log","message":"1"}]},
              {"id":"dup","name":"B","triggers":[{"type":"on_startup"}],
               "actions":[{"type":"log","message":"2"}]}]}""",
        )
        assertTrue(codes(d.errors).contains("duplicate_rule_id"))
        assertEquals(1, d.rules.size)
    }

    @Test
    fun allTriggerTypesParse() {
        val d = ScriptEngine.parseDocument(
            """{"rules":[{"id":"a","name":"A","triggers":[
              {"type":"on_startup"},{"type":"on_interval","milliseconds":5000},
              {"type":"on_module_toggle","moduleId":"fps"},
              {"type":"on_profile_change"},{"type":"on_theme_change"},
              {"type":"on_setting_change","section":"client","key":"theme"},
              {"type":"on_hud_element_change","elementId":"fps"}],
              "actions":[{"type":"log","message":"m"}]}]}""",
        )
        assertTrue(d.ok)
        assertEquals(7, d.rules[0].triggers.size)
    }

    @Test
    fun allConditionTypesParse() {
        val d = ScriptEngine.parseDocument(
            """{"rules":[{"id":"a","name":"A","triggers":[{"type":"on_startup"}],
              "conditions":[
                {"type":"module_enabled","moduleId":"fps"},
                {"type":"profile_equals","profileName":"Default"},
                {"type":"theme_equals","themeName":"Xykell Dark"},
                {"type":"setting_equals","section":"client","key":"theme","value":"v"},
                {"type":"time_between","startHour":9,"endHour":17},
                {"type":"and","conditions":[{"type":"module_enabled","moduleId":"fps"}]},
                {"type":"or","conditions":[{"type":"module_enabled","moduleId":"fps"}]},
                {"type":"not","condition":{"type":"module_enabled","moduleId":"fps"}}],
              "actions":[{"type":"log","message":"m"}]}]}""",
        )
        assertTrue(d.ok)
        assertEquals(8, d.rules[0].conditions.size)
    }

    @Test
    fun allActionTypesParse() {
        val d = ScriptEngine.parseDocument(
            """{"rules":[{"id":"a","name":"A","triggers":[{"type":"on_startup"}],
              "actions":[
                {"type":"set_module_enabled","moduleId":"fps","enabled":true},
                {"type":"set_profile","profileName":"Default"},
                {"type":"set_theme","themeName":"Xykell Dark"},
                {"type":"set_setting","section":"client","key":"theme","value":"v"},
                {"type":"set_hud_element_visible","elementId":"fps","visible":true},
                {"type":"set_hud_element_position","elementId":"fps","x":1,"y":2},
                {"type":"set_hud_element_scale","elementId":"fps","scale":1.5},
                {"type":"log","message":"m"},
                {"type":"notify","title":"t","message":"m"}]}]}""",
        )
        assertTrue(d.ok)
        assertEquals(9, d.rules[0].actions.size)
    }

    // --- Evaluation

    @Test
    fun evaluateEmptyConditionsIsTrue() {
        assertTrue(ScriptEngine.evaluateConditions(emptyList(), ctx()))
    }

    @Test
    fun evaluateModuleEnabled() {
        val c = listOf<ScriptEngine.Condition>(
            ScriptEngine.Condition.ModuleEnabled("fps"),
        )
        assertTrue(ScriptEngine.evaluateConditions(c, ctx(modules = mapOf("fps" to true))))
        assertFalse(ScriptEngine.evaluateConditions(c, ctx(modules = mapOf("fps" to false))))
        assertFalse(ScriptEngine.evaluateConditions(c, ctx()))
    }

    @Test
    fun evaluateProfileAndThemeEquals() {
        assertTrue(
            ScriptEngine.evaluateConditions(
                listOf(ScriptEngine.Condition.ProfileEquals("Default")), ctx(profile = "Default"),
            ),
        )
        assertFalse(
            ScriptEngine.evaluateConditions(
                listOf(ScriptEngine.Condition.ThemeEquals("Light")), ctx(theme = "Xykell Dark"),
            ),
        )
    }

    @Test
    fun evaluateSettingEqualsDistinguishesMissingFromEmpty() {
        val c = listOf<ScriptEngine.Condition>(
            ScriptEngine.Condition.SettingEquals("client", "k", ""),
        )
        assertTrue(
            ScriptEngine.evaluateConditions(
                c, ctx(settings = mapOf("client/k" to "")),
            ),
        )
        assertFalse(ScriptEngine.evaluateConditions(c, ctx()))
    }

    @Test
    fun evaluateTimeBetween() {
        val c = listOf<ScriptEngine.Condition>(ScriptEngine.Condition.TimeBetween(9, 17))
        assertTrue(ScriptEngine.evaluateConditions(c, ctx(hour = 12)))
        assertFalse(ScriptEngine.evaluateConditions(c, ctx(hour = 22)))
    }

    @Test
    fun evaluateTimeBetweenWrapsMidnight() {
        val c = listOf<ScriptEngine.Condition>(ScriptEngine.Condition.TimeBetween(22, 6))
        assertTrue(ScriptEngine.evaluateConditions(c, ctx(hour = 23)))
        assertTrue(ScriptEngine.evaluateConditions(c, ctx(hour = 2)))
        assertFalse(ScriptEngine.evaluateConditions(c, ctx(hour = 12)))
    }

    @Test
    fun evaluateBooleanCombinators() {
        val yes = ScriptEngine.Condition.ModuleEnabled("fps")
        val no = ScriptEngine.Condition.ModuleEnabled("cps")
        val m = mapOf("fps" to true, "cps" to false)
        assertTrue(
            ScriptEngine.evaluateConditions(
                listOf(ScriptEngine.Condition.And(listOf(yes, ScriptEngine.Condition.Not(no)))),
                ctx(modules = m),
            ),
        )
        assertTrue(
            ScriptEngine.evaluateConditions(
                listOf(ScriptEngine.Condition.Or(listOf(no, yes))), ctx(modules = m),
            ),
        )
        assertFalse(
            ScriptEngine.evaluateConditions(
                listOf(ScriptEngine.Condition.And(listOf(yes, no))), ctx(modules = m),
            ),
        )
    }

    @Test
    fun evaluationIsDeterministic() {
        val c = listOf(
            ScriptEngine.Condition.ModuleEnabled("fps"),
            ScriptEngine.Condition.TimeBetween(9, 17),
        )
        val first = ScriptEngine.evaluateConditions(c, ctx(modules = mapOf("fps" to true)))
        repeat(50) {
            assertEquals(first, ScriptEngine.evaluateConditions(c, ctx(modules = mapOf("fps" to true))))
        }
    }

    // --- Serialization

    @Test
    fun serializeRoundTripsThroughParse() {
        val original = ScriptEngine.parseDocument(
            """{"rules":[{"id":"a","name":"Rule A","enabled":false,
               "triggers":[{"type":"on_interval","milliseconds":5000}],
               "conditions":[{"type":"not","condition":{"type":"module_enabled","moduleId":"fps"}}],
               "actions":[{"type":"set_hud_element_position","elementId":"fps","x":3,"y":4}]}]}""",
        ).rules
        val text = ScriptEngine.serialize(original)
        val again = ScriptEngine.parseDocument(text)
        assertTrue(again.ok)
        assertEquals(ScriptEngine.serialize(original), ScriptEngine.serialize(again.rules))
    }

    @Test
    fun serializedOutputIsByteStable() {
        val r = ScriptEngine.Rule(
            "stable", "Stable", true,
            listOf(ScriptEngine.Trigger.OnStartup),
            listOf(ScriptEngine.Condition.ThemeEquals("Xykell Dark")),
            listOf(ScriptEngine.Action.Log("m")),
        )
        val a = ScriptEngine.serialize(listOf(r))
        repeat(20) { assertEquals(a, ScriptEngine.serialize(listOf(r))) }
    }

    @Test
    fun serializerEscapesQuotesAndControlCharacters() {
        val r = ScriptEngine.Rule(
            "esc", "Quote \" and \\ and \n newline", true,
            listOf(ScriptEngine.Trigger.OnStartup), emptyList(),
            listOf(ScriptEngine.Action.Log("tab\there")),
        )
        val text = ScriptEngine.serialize(listOf(r))
        assertTrue(text.contains("\\\"" ))
        assertTrue(text.contains("\\\\"))
        assertTrue(text.contains("\\n"))
        assertTrue(text.contains("\\t"))
        // Still parses back to the same content.
        val back = ScriptEngine.parseDocument(text)
        assertTrue(back.ok)
        assertEquals("Quote \" and \\ and \n newline", back.rules[0].name)
    }

    @Test
    fun serializeEmptyProducesValidDocument() {
        assertEquals("""{"schemaVersion":2,"rules":[]}""", ScriptEngine.serialize(emptyList()))
        assertTrue(ScriptEngine.parseDocument(ScriptEngine.serialize(emptyList())).ok)
    }

    @Test
    fun documentIsSortedByIdOnParse() {
        val d = ScriptEngine.parseDocument(
            """{"rules":[
              {"id":"z","name":"Z","triggers":[{"type":"on_startup"}],
               "actions":[{"type":"log","message":"z"}]},
              {"id":"a","name":"A","triggers":[{"type":"on_startup"}],
               "actions":[{"type":"log","message":"a"}]}]}""",
        )
        assertTrue(d.ok)
        assertEquals(listOf("a", "z"), d.rules.map { it.id })
    }

    // --- Trigger matching

    @Test
    fun matchesOnlyTheMatchingEvent() {
        val t = ScriptEngine.Trigger.OnModuleToggle("fps")
        assertTrue(ScriptEngine.matches(t, ScriptRuntime.Event.ModuleToggled("fps", true)))
        assertFalse(ScriptEngine.matches(t, ScriptRuntime.Event.ModuleToggled("cps", true)))
        assertFalse(ScriptEngine.matches(t, ScriptRuntime.Event.Startup))
    }
}
