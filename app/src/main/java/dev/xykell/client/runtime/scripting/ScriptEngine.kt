package dev.xykell.client.runtime.scripting

import org.json.JSONArray
import org.json.JSONObject

/**
 * Safe local rule engine for Xykell Client.
 * Implements a declarative rule system without arbitrary code execution.
 * Rules are JSON-defined conditions and actions operating on Xykell-owned state only.
 */
object ScriptEngine {

    data class Rule(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val triggers: List<Trigger>,
        val conditions: List<Condition>,
        val actions: List<Action>
    )

sealed class Trigger {
        data class OnModuleToggle(val moduleId: String) : Trigger()
        data class OnProfileChange(val dummy: Unit = Unit) : Trigger()
        data class OnThemeChange(val dummy: Unit = Unit) : Trigger()
        data class OnSettingChange(val section: String, val key: String) : Trigger()
        data class OnStartup(val dummy: Unit = Unit) : Trigger()
        data class OnInterval(val milliseconds: Long) : Trigger()
        data class OnHudElementChange(val elementId: String) : Trigger()
    }

    sealed class Condition {
        data class ModuleEnabled(val moduleId: String) : Condition()
        data class ProfileEquals(val profileName: String) : Condition()
        data class ThemeEquals(val themeName: String) : Condition()
        data class SettingEquals(val section: String, val key: String, val value: String) : Condition()
        data class TimeBetween(val startHour: Int, val endHour: Int) : Condition()
        data class And(val conditions: List<Condition>) : Condition()
        data class Or(val conditions: List<Condition>) : Condition()
        data class Not(val condition: Condition) : Condition()
    }

    sealed class Action {
        data class SetModuleEnabled(val moduleId: String, val enabled: Boolean) : Action()
        data class SetProfile(val profileName: String) : Action()
        data class SetTheme(val themeName: String) : Action()
        data class SetSetting(val section: String, val key: String, val value: String) : Action()
        data class SetHudElementVisible(val elementId: String, val visible: Boolean) : Action()
        data class SetHudElementPosition(val elementId: String, val x: Int, val y: Int) : Action()
        data class SetHudElementScale(val elementId: String, val scale: Float) : Action()
        data class Log(val message: String) : Action()
        data class Notify(val title: String, val message: String) : Action()
    }

    /** Parse rules from JSON. Returns null on parse error. */
    fun parseRules(json: String): List<Rule>? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val arr = root.optJSONArray("rules") ?: return emptyList()
        val out = mutableListOf<Rule>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rule = parseRule(o) ?: continue
            out.add(rule)
        }
        return out
    }

    private fun parseRule(o: JSONObject): Rule? {
        val id = o.optString("id", "")
        val name = o.optString("name", "")
        val enabled = o.optBoolean("enabled", true)
        val triggers = parseTriggers(o.optJSONArray("triggers"))
        val conditions = parseConditions(o.optJSONArray("conditions"))
        val actions = parseActions(o.optJSONArray("actions"))
        if (id.isEmpty() || name.isEmpty() || triggers.isEmpty()) {
            return null
        }
        return Rule(id, name, enabled, triggers, conditions, actions)
    }

    private fun parseTriggers(arr: JSONArray?): List<Trigger> {
        val out = mutableListOf<Trigger>()
        arr?.let {
            for (i in 0 until it.length()) {
                parseTrigger(it.optJSONObject(i))?.let { out.add(it) }
            }
        }
        return out
    }

    private fun parseTrigger(o: JSONObject?): Trigger? = o?.let {
        val type = it.optString("type", "")
        return when (type) {
            "on_module_toggle" -> Trigger.OnModuleToggle(it.optString("moduleId", ""))
            "on_profile_change" -> Trigger.OnProfileChange()
            "on_theme_change" -> Trigger.OnThemeChange()
            "on_setting_change" -> Trigger.OnSettingChange(
                it.optString("section", ""), it.optString("key", "")
            )
            "on_startup" -> Trigger.OnStartup()
            "on_interval" -> Trigger.OnInterval(it.optLong("milliseconds", 0))
            "on_hud_element_change" -> Trigger.OnHudElementChange(it.optString("elementId", ""))
            else -> null
        }
    }

    private fun parseConditions(arr: JSONArray?): List<Condition> {
        val out = mutableListOf<Condition>()
        arr?.let {
            for (i in 0 until it.length()) {
                parseCondition(it.optJSONObject(i))?.let { out.add(it) }
            }
        }
        return out
    }

    private fun parseCondition(o: JSONObject?): Condition? = o?.let {
        val type = it.optString("type", "")
        return when (type) {
            "module_enabled" -> Condition.ModuleEnabled(it.optString("moduleId", ""))
            "profile_equals" -> Condition.ProfileEquals(it.optString("profileName", ""))
            "theme_equals" -> Condition.ThemeEquals(it.optString("themeName", ""))
            "setting_equals" -> Condition.SettingEquals(
                it.optString("section", ""), it.optString("key", ""), it.optString("value", "")
            )
            "time_between" -> Condition.TimeBetween(it.optInt("startHour", 0), it.optInt("endHour", 23))
            "and" -> Condition.And(parseConditions(it.optJSONArray("conditions")))
            "or" -> Condition.Or(parseConditions(it.optJSONArray("conditions")))
            "not" -> Condition.Not(parseCondition(it.optJSONObject("condition")) ?: return null)
            else -> null
        }
    }

    private fun parseActions(arr: JSONArray?): List<Action> {
        val out = mutableListOf<Action>()
        arr?.let {
            for (i in 0 until it.length()) {
                parseAction(it.optJSONObject(i))?.let { out.add(it) }
            }
        }
        return out
    }

    private fun parseAction(o: JSONObject?): Action? = o?.let {
        val type = it.optString("type", "")
        return when (type) {
            "set_module_enabled" -> Action.SetModuleEnabled(
                it.optString("moduleId", ""), it.optBoolean("enabled", true)
            )
            "set_profile" -> Action.SetProfile(it.optString("profileName", ""))
            "set_theme" -> Action.SetTheme(it.optString("themeName", ""))
            "set_setting" -> Action.SetSetting(
                it.optString("section", ""), it.optString("key", ""), it.optString("value", "")
            )
            "set_hud_element_visible" -> Action.SetHudElementVisible(
                it.optString("elementId", ""), it.optBoolean("visible", true)
            )
            "set_hud_element_position" -> Action.SetHudElementPosition(
                it.optString("elementId", ""), it.optInt("x", 0), it.optInt("y", 0)
            )
            "set_hud_element_scale" -> Action.SetHudElementScale(
                it.optString("elementId", ""), it.optDouble("scale", 1.0).toFloat()
            )
            "log" -> Action.Log(it.optString("message", ""))
            "notify" -> Action.Notify(
                it.optString("title", ""), it.optString("message", "")
            )
            else -> null
        }
    }

    /** Evaluate a rule against current state. Returns true if all conditions pass. */
    fun evaluateConditions(conditions: List<Condition>, context: EvalContext): Boolean {
        for (c in conditions) {
            if (!evalCondition(c, context)) return false
        }
        return true
    }

    fun serializeRules(rules: List<Rule>): String {
        val arr = org.json.JSONArray()
        for (rule in rules) {
            arr.put(ruleToJson(rule))
        }
        return org.json.JSONObject().put("rules", arr).toString()
    }

    private fun ruleToJson(rule: Rule): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("id", rule.id)
            put("name", rule.name)
            put("enabled", rule.enabled)
            put("triggers", triggersToJson(rule.triggers))
            put("conditions", conditionsToJson(rule.conditions))
            put("actions", actionsToJson(rule.actions))
        }
    }

    private fun triggersToJson(triggers: List<Trigger>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (t in triggers) {
            arr.put(triggerToJson(t))
        }
        return arr
    }

    private fun triggerToJson(t: Trigger): org.json.JSONObject {
        return org.json.JSONObject().apply {
            when (t) {
                is Trigger.OnModuleToggle -> put("type", "on_module_toggle").put("moduleId", t.moduleId)
                is Trigger.OnProfileChange -> put("type", "on_profile_change")
                is Trigger.OnThemeChange -> put("type", "on_theme_change")
                is Trigger.OnSettingChange -> {
                    put("type", "on_setting_change")
                    put("section", t.section)
                    put("key", t.key)
                }
                is Trigger.OnStartup -> put("type", "on_startup")
                is Trigger.OnInterval -> {
                    put("type", "on_interval")
                    put("milliseconds", t.milliseconds)
                }
                is Trigger.OnHudElementChange -> {
                    put("type", "on_hud_element_change")
                    put("elementId", t.elementId)
                }
            }
        }
    }

    private fun conditionsToJson(conditions: List<Condition>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (c in conditions) {
            arr.put(conditionToJson(c))
        }
        return arr
    }

    private fun conditionToJson(c: Condition): org.json.JSONObject {
        return org.json.JSONObject().apply {
            when (c) {
                is Condition.ModuleEnabled -> {
                    put("type", "module_enabled")
                    put("moduleId", c.moduleId)
                }
                is Condition.ProfileEquals -> {
                    put("type", "profile_equals")
                    put("profileName", c.profileName)
                }
                is Condition.ThemeEquals -> {
                    put("type", "theme_equals")
                    put("themeName", c.themeName)
                }
                is Condition.SettingEquals -> {
                    put("type", "setting_equals")
                    put("section", c.section)
                    put("key", c.key)
                    put("value", c.value)
                }
                is Condition.TimeBetween -> {
                    put("type", "time_between")
                    put("startHour", c.startHour)
                    put("endHour", c.endHour)
                }
                is Condition.And -> {
                    put("type", "and")
                    put("conditions", conditionsToJson(c.conditions))
                }
                is Condition.Or -> {
                    put("type", "or")
                    put("conditions", conditionsToJson(c.conditions))
                }
                is Condition.Not -> {
                    put("type", "not")
                    put("condition", conditionToJson(c.condition))
                }
            }
        }
    }

    private fun actionsToJson(actions: List<Action>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (a in actions) {
            arr.put(actionToJson(a))
        }
        return arr
    }

    private fun actionToJson(a: Action): org.json.JSONObject {
        return org.json.JSONObject().apply {
            when (a) {
                is Action.SetModuleEnabled -> {
                    put("type", "set_module_enabled")
                    put("moduleId", a.moduleId)
                    put("enabled", a.enabled)
                }
                is Action.SetProfile -> {
                    put("type", "set_profile")
                    put("profileName", a.profileName)
                }
                is Action.SetTheme -> {
                    put("type", "set_theme")
                    put("themeName", a.themeName)
                }
                is Action.SetSetting -> {
                    put("type", "set_setting")
                    put("section", a.section)
                    put("key", a.key)
                    put("value", a.value)
                }
                is Action.SetHudElementVisible -> {
                    put("type", "set_hud_element_visible")
                    put("elementId", a.elementId)
                    put("visible", a.visible)
                }
                is Action.SetHudElementPosition -> {
                    put("type", "set_hud_element_position")
                    put("elementId", a.elementId)
                    put("x", a.x)
                    put("y", a.y)
                }
                is Action.SetHudElementScale -> {
                    put("type", "set_hud_element_scale")
                    put("elementId", a.elementId)
                    put("scale", a.scale)
                }
                is Action.Log -> {
                    put("type", "log")
                    put("message", a.message)
                }
                is Action.Notify -> {
                    put("type", "notify")
                    put("title", a.title)
                    put("message", a.message)
                }
            }
        }
    }

    private fun evalCondition(c: Condition, ctx: EvalContext): Boolean = when (c) {
        is Condition.ModuleEnabled -> ctx.moduleEnabled(c.moduleId)
        is Condition.ProfileEquals -> ctx.currentProfile == c.profileName
        is Condition.ThemeEquals -> ctx.currentTheme == c.themeName
        is Condition.SettingEquals -> ctx.getSetting(c.section, c.key) == c.value
        is Condition.TimeBetween -> {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            hour in c.startHour..c.endHour
        }
        is Condition.And -> c.conditions.all { evalCondition(it, ctx) }
        is Condition.Or -> c.conditions.any { evalCondition(it, ctx) }
        is Condition.Not -> !evalCondition(c.condition, ctx)
    }

    data class EvalContext(
        val moduleEnabled: (String) -> Boolean,
        val currentProfile: String,
        val currentTheme: String,
        val getSetting: (String, String) -> String?
    )
}