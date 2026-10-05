package dev.xykell.client.runtime.scripting

import android.content.Context
import android.content.SharedPreferences
import dev.xykell.client.NativeSettings
import org.json.JSONObject

/**
 * ScriptManager: manages safe local rules for Xykell Client.
 * Rules are JSON-defined conditions and actions operating on Xykell-owned state only.
 * No arbitrary code execution, no shell access, no network, no filesystem beyond app sandbox.
 */
object ScriptManager {

    private const val PREFS_KEY = "script_rules"
    private var rulesCache: List<ScriptEngine.Rule> = emptyList()
    private var evaluator: ((ScriptEngine.EvalContext) -> Unit)? = null

    /** Initialize and load rules from storage. */
    fun initialize(context: Context) {
        loadRules(context)
    }

    /** Set the evaluator callback for rule execution. */
    fun setEvaluator(eval: (ScriptEngine.EvalContext) -> Unit) {
        evaluator = eval
    }

    /** Get all stored rules. */
    fun getRules(): List<ScriptEngine.Rule> = rulesCache

    /** Add or update a rule. */
    fun addRule(context: Context, rule: ScriptEngine.Rule): Boolean {
        val updated = rulesCache.toMutableList().apply {
            val idx = indexOfFirst { it.id == rule.id }
            if (idx >= 0) set(idx, rule) else add(rule)
        }
        return saveRules(context, updated)
    }

    /** Remove a rule by ID. */
    fun removeRule(context: Context, ruleId: String): Boolean {
        val updated = rulesCache.filter { it.id != ruleId }
        return saveRules(context, updated)
    }

    /** Enable or disable a rule. */
    fun setRuleEnabled(context: Context, ruleId: String, enabled: Boolean): Boolean {
        val idx = rulesCache.indexOfFirst { it.id == ruleId }
        if (idx < 0) return false
        val updated = rulesCache.toMutableList().apply {
            val rule = this[idx]
            this[idx] = rule.copy(enabled = enabled)
        }
        return saveRules(context, updated)
    }

    /** Evaluate all enabled rules against current state. */
    fun evaluate(context: Context) {
        evaluator?.let { eval ->
            val ctx = buildEvalContext(context)
            for (rule in rulesCache) {
                if (rule.enabled && ScriptEngine.evaluateConditions(rule.conditions, ctx)) {
                    executeActions(context, rule.actions)
                }
            }
        }
    }

    private fun buildEvalContext(context: Context): ScriptEngine.EvalContext {
        return ScriptEngine.EvalContext(
            moduleEnabled = { id ->
                try {
                    val values = org.json.JSONObject(NativeSettings.getValues(NativeSettings.root(context)))
                    values.optJSONObject("modules")?.optBoolean(id, false) ?: false
                } catch (e: Exception) {
                    false
                }
            },
            currentProfile = dev.xykell.client.runtime.ProfileManager.currentProfile,
            currentTheme = getCurrentTheme(context),
            getSetting = { section, key ->
                try {
                    val values = org.json.JSONObject(NativeSettings.getValues(NativeSettings.root(context)))
                    values.optJSONObject(section)?.optString(key) ?: ""
                } catch (e: Exception) {
                    ""
                }
            }
        )
    }

    private fun getCurrentTheme(context: Context): String {
        return try {
            val values = org.json.JSONObject(NativeSettings.getValues(NativeSettings.root(context)))
            values.optJSONObject("client")?.optString("theme", "") ?: dev.xykell.client.ui.ThemeColors.DEFAULT.name
        } catch (e: Exception) {
            dev.xykell.client.ui.ThemeColors.DEFAULT.name
        }
    }

    private fun executeActions(context: Context, actions: List<ScriptEngine.Action>) {
        for (action in actions) {
            when (action) {
                is ScriptEngine.Action.SetModuleEnabled -> {
                    // Module toggles are handled by NativeHud via profile settings
                    // This would need a native bridge method to set module state
                }
                is ScriptEngine.Action.SetProfile -> {
                    dev.xykell.client.runtime.ProfileManager.select(action.profileName)
                }
                is ScriptEngine.Action.SetTheme -> {
                    val root = dev.xykell.client.NativeSettings.root(context)
                    dev.xykell.client.NativeSettings.set(root, "client", "theme", org.json.JSONObject.quote(action.themeName))
                }
                is ScriptEngine.Action.SetSetting -> {
                    val root = dev.xykell.client.NativeSettings.root(context)
                    dev.xykell.client.NativeSettings.set(
                        dev.xykell.client.NativeSettings.root(context),
                        action.section, action.key, org.json.JSONObject.quote(action.value)
                    )
                }
                is ScriptEngine.Action.SetHudElementVisible -> {
                    // HUD element modifications via NativeHud
                }
                is ScriptEngine.Action.SetHudElementPosition -> {
                    // HUD element modifications via NativeHud
                }
                is ScriptEngine.Action.SetHudElementScale -> {
                    // HUD element modifications via NativeHud
                }
                is ScriptEngine.Action.Log -> {
                    // Log to internal log
                }
                is ScriptEngine.Action.Notify -> {
                    // Could show a toast or notification
                }
            }
        }
    }

    private fun loadRules(context: Context) {
        val prefs = context.getSharedPreferences("xykell_scripting", Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_KEY, "") ?: ""
        rulesCache = ScriptEngine.parseRules(json) ?: emptyList()
    }

    private fun saveRules(context: Context, rules: List<ScriptEngine.Rule>): Boolean {
        val prefs = context.getSharedPreferences("xykell_scripting", Context.MODE_PRIVATE)
        val json = ScriptEngine.serializeRules(rules)
        return prefs.edit().putString(PREFS_KEY, json).commit()
    }

    fun serializeRules(rules: List<ScriptEngine.Rule>): String {
        return org.json.JSONObject().put("rules", toJsonArray(rules)).toString()
    }

    private fun toJsonArray(rules: List<ScriptEngine.Rule>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (rule in rules) {
            arr.put(ruleToJson(rule))
        }
        return arr
    }

    private fun ruleToJson(rule: ScriptEngine.Rule): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("id", rule.id)
            put("name", rule.name)
            put("enabled", rule.enabled)
            put("triggers", triggersToJson(rule.triggers))
            put("conditions", conditionsToJson(rule.conditions))
            put("actions", actionsToJson(rule.actions))
        }
    }

    private fun triggersToJson(triggers: List<ScriptEngine.Trigger>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (t in triggers) {
            arr.put(triggerToJson(t))
        }
        return arr
    }

    private fun triggerToJson(t: ScriptEngine.Trigger): org.json.JSONObject {
        return org.json.JSONObject().apply {
            when (t) {
                is ScriptEngine.Trigger.OnModuleToggle -> put("type", "on_module_toggle").put("moduleId", t.moduleId)
                is ScriptEngine.Trigger.OnProfileChange -> put("type", "on_profile_change")
                is ScriptEngine.Trigger.OnThemeChange -> put("type", "on_theme_change")
                is ScriptEngine.Trigger.OnSettingChange -> {
                    put("type", "on_setting_change")
                    put("section", t.section)
                    put("key", t.key)
                }
                is ScriptEngine.Trigger.OnStartup -> put("type", "on_startup")
                is ScriptEngine.Trigger.OnInterval -> {
                    put("type", "on_interval")
                    put("milliseconds", t.milliseconds)
                }
                is ScriptEngine.Trigger.OnHudElementChange -> {
                    put("type", "on_hud_element_change")
                    put("elementId", t.elementId)
                }
            }
        }
    }

    private fun conditionsToJson(conditions: List<ScriptEngine.Condition>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (c in conditions) {
            arr.put(conditionToJson(c))
        }
        return arr
    }

    private fun conditionToJson(c: ScriptEngine.Condition): org.json.JSONObject {
        return org.json.JSONObject().apply {
            when (c) {
                is ScriptEngine.Condition.ModuleEnabled -> {
                    put("type", "module_enabled")
                    put("moduleId", c.moduleId)
                }
                is ScriptEngine.Condition.ProfileEquals -> {
                    put("type", "profile_equals")
                    put("profileName", c.profileName)
                }
                is ScriptEngine.Condition.ThemeEquals -> {
                    put("type", "theme_equals")
                    put("themeName", c.themeName)
                }
                is ScriptEngine.Condition.SettingEquals -> {
                    put("type", "setting_equals")
                    put("section", c.section)
                    put("key", c.key)
                    put("value", c.value)
                }
                is ScriptEngine.Condition.TimeBetween -> {
                    put("type", "time_between")
                    put("startHour", c.startHour)
                    put("endHour", c.endHour)
                }
                is ScriptEngine.Condition.And -> {
                    put("type", "and")
                    put("conditions", conditionsToJson(c.conditions))
                }
                is ScriptEngine.Condition.Or -> {
                    put("type", "or")
                    put("conditions", conditionsToJson(c.conditions))
                }
                is ScriptEngine.Condition.Not -> {
                    put("type", "not")
                    put("condition", conditionToJson(c.condition))
                }
            }
        }
    }

    private fun actionsToJson(actions: List<ScriptEngine.Action>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (a in actions) {
            arr.put(actionToJson(a))
        }
        return arr
    }

    private fun actionToJson(a: ScriptEngine.Action): org.json.JSONObject {
        return org.json.JSONObject().apply {
            when (a) {
                is ScriptEngine.Action.SetModuleEnabled -> {
                    put("type", "set_module_enabled")
                    put("moduleId", a.moduleId)
                    put("enabled", a.enabled)
                }
                is ScriptEngine.Action.SetProfile -> {
                    put("type", "set_profile")
                    put("profileName", a.profileName)
                }
                is ScriptEngine.Action.SetTheme -> {
                    put("type", "set_theme")
                    put("themeName", a.themeName)
                }
                is ScriptEngine.Action.SetSetting -> {
                    put("type", "set_setting")
                    put("section", a.section)
                    put("key", a.key)
                    put("value", a.value)
                }
                is ScriptEngine.Action.SetHudElementVisible -> {
                    put("type", "set_hud_element_visible")
                    put("elementId", a.elementId)
                    put("visible", a.visible)
                }
                is ScriptEngine.Action.SetHudElementPosition -> {
                    put("type", "set_hud_element_position")
                    put("elementId", a.elementId)
                    put("x", a.x)
                    put("y", a.y)
                }
                is ScriptEngine.Action.SetHudElementScale -> {
                    put("type", "set_hud_element_scale")
                    put("elementId", a.elementId)
                    put("scale", a.scale)
                }
                is ScriptEngine.Action.Log -> {
                    put("type", "log")
                    put("message", a.message)
                }
                is ScriptEngine.Action.Notify -> {
                    put("type", "notify")
                    put("title", a.title)
                    put("message", a.message)
                }
            }
        }
    }
}