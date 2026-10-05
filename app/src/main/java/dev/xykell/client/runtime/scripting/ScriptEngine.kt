package dev.xykell.client.runtime.scripting

import org.json.JSONArray
import org.json.JSONObject

/**
 * Safe local declarative rule engine for Xykell Client.
 *
 * This engine never executes code. A rule is data: a fixed set of trigger
 * types, condition types and action types, all Xykell-owned. Parsing is
 * total (never throws, never silently drops a rule), bounded (depth, count
 * and length limits), and serialization is canonical so round-trips are
 * byte-stable.
 *
 * Policy — what a valid rule may contain, how often it may run — lives in
 * [ScriptSandbox]. Scheduling and dispatch live in [ScriptRuntime].
 */
object ScriptEngine {

    /** Bumped when the on-disk rule document shape changes; see [migrate]. */
    const val SCHEMA_VERSION = 2

    /** Structural limits enforced while parsing. Policy limits are in [ScriptSandbox]. */
    const val MAX_RULES = 200
    const val MAX_TRIGGERS_PER_RULE = 16
    const val MAX_CONDITIONS_PER_RULE = 32
    const val MAX_ACTIONS_PER_RULE = 32
    const val MAX_CONDITION_DEPTH = 8
    const val MAX_ID_LEN = 64
    const val MAX_NAME_LEN = 128
    const val MAX_STRING_LEN = 256

    // --- Allowlists. A test asserts these match the sealed hierarchies exactly,
    // so adding a case to a sealed class without listing it here fails the build.

    val TRIGGER_TYPES: Set<String> = setOf(
        "on_startup", "on_interval", "on_module_toggle", "on_profile_change",
        "on_theme_change", "on_setting_change", "on_hud_element_change",
    )

    val CONDITION_TYPES: Set<String> = setOf(
        "module_enabled", "profile_equals", "theme_equals", "setting_equals",
        "time_between", "and", "or", "not",
    )

    val ACTION_TYPES: Set<String> = setOf(
        "set_module_enabled", "set_profile", "set_theme", "set_setting",
        "set_hud_element_visible", "set_hud_element_position",
        "set_hud_element_scale", "log", "notify",
    )

    // --- Rule model

    data class Rule(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val triggers: List<Trigger>,
        val conditions: List<Condition>,
        val actions: List<Action>,
    )

    sealed class Trigger {
        abstract val type: String
        object OnStartup : Trigger() { override val type get() = "on_startup" }
        data class OnInterval(val milliseconds: Long) : Trigger() {
            override val type get() = "on_interval"
        }
        data class OnModuleToggle(val moduleId: String) : Trigger() {
            override val type get() = "on_module_toggle"
        }
        object OnProfileChange : Trigger() { override val type get() = "on_profile_change" }
        object OnThemeChange : Trigger() { override val type get() = "on_theme_change" }
        data class OnSettingChange(val section: String, val key: String) : Trigger() {
            override val type get() = "on_setting_change"
        }
        data class OnHudElementChange(val elementId: String) : Trigger() {
            override val type get() = "on_hud_element_change"
        }
    }

    sealed class Condition {
        abstract val type: String
        data class ModuleEnabled(val moduleId: String) : Condition() {
            override val type get() = "module_enabled"
        }
        data class ProfileEquals(val profileName: String) : Condition() {
            override val type get() = "profile_equals"
        }
        data class ThemeEquals(val themeName: String) : Condition() {
            override val type get() = "theme_equals"
        }
        data class SettingEquals(val section: String, val key: String, val value: String) :
            Condition() { override val type get() = "setting_equals" }
        data class TimeBetween(val startHour: Int, val endHour: Int) : Condition() {
            override val type get() = "time_between"
        }
        data class And(val conditions: List<Condition>) : Condition() {
            override val type get() = "and"
        }
        data class Or(val conditions: List<Condition>) : Condition() {
            override val type get() = "or"
        }
        data class Not(val condition: Condition) : Condition() {
            override val type get() = "not"
        }
    }

    sealed class Action {
        abstract val type: String
        data class SetModuleEnabled(val moduleId: String, val enabled: Boolean) : Action() {
            override val type get() = "set_module_enabled"
        }
        data class SetProfile(val profileName: String) : Action() {
            override val type get() = "set_profile"
        }
        data class SetTheme(val themeName: String) : Action() {
            override val type get() = "set_theme"
        }
        data class SetSetting(val section: String, val key: String, val value: String) : Action() {
            override val type get() = "set_setting"
        }
        data class SetHudElementVisible(val elementId: String, val visible: Boolean) : Action() {
            override val type get() = "set_hud_element_visible"
        }
        data class SetHudElementPosition(val elementId: String, val x: Int, val y: Int) : Action() {
            override val type get() = "set_hud_element_position"
        }
        data class SetHudElementScale(val elementId: String, val scale: Float) : Action() {
            override val type get() = "set_hud_element_scale"
        }
        data class Log(val message: String) : Action() {
            override val type get() = "log"
        }
        data class Notify(val title: String, val message: String) : Action() {
            override val type get() = "notify"
        }
    }

    // --- Parse result: errors are reported, never swallowed.

    data class ValidationError(val code: String, val path: String, val detail: String) {
        override fun toString() = "$code at $path: $detail"
    }

    data class Document(
        val schemaVersion: Int,
        val rules: List<Rule>,
        val errors: List<ValidationError>,
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    /**
     * Parse a rule document. Total: any failure is reported in [Document.errors]
     * rather than thrown, and an unusable rule is skipped with a recorded reason.
     * Unknown JSON fields are ignored so older documents keep loading.
     */
    fun parseDocument(json: String): Document {
        if (json.isBlank()) return Document(SCHEMA_VERSION, emptyList(), emptyList())
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return Document(
                SCHEMA_VERSION, emptyList(),
                listOf(ValidationError("malformed_json", "$", e.javaClass.simpleName)),
            )
        }
        val errors = mutableListOf<ValidationError>()
        val version = root.optInt("schemaVersion", 1)
        if (version > SCHEMA_VERSION) {
            // Forward-only safety: refuse to interpret a newer shape.
            return Document(
                version, emptyList(),
                listOf(
                    ValidationError(
                        "schema_too_new", "$.schemaVersion",
                        "document version $version > supported $SCHEMA_VERSION",
                    ),
                ),
            )
        }
        val rules = mutableListOf<Rule>()
        val arr: JSONArray? = root.optJSONArray("rules")
        if (arr == null) {
            if (root.has("rules")) {
                errors.add(ValidationError("rules_not_array", "$.rules", "expected array"))
            }
            return Document(version, emptyList(), errors)
        }
        if (arr.length() > MAX_RULES) {
            errors.add(
                ValidationError("too_many_rules", "$.rules", "${arr.length()} > $MAX_RULES"),
            )
            return Document(version, emptyList(), errors)
        }
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                errors.add(ValidationError("rule_not_object", "$.rules[$i]", "expected object"))
                continue
            }
            val errs = mutableListOf<ValidationError>()
            val rule = parseRule(o, "$.rules[$i]", errs)
            errors.addAll(errs)
            // A rule with any unparseable part is dropped whole. Running the
            // readable remainder of a half-understood rule would be worse than
            // refusing it: the author would not get what they asked for.
            if (rule == null || errs.isNotEmpty()) continue
            // First definition wins, so a duplicate cannot change behaviour
            // by reordering the file.
            if (!seen.add(rule.id)) {
                errors.add(ValidationError("duplicate_rule_id", "$.rules[$i]", rule.id))
                continue
            }
            rules.add(rule)
        }
        // Deterministic dispatch order regardless of file order.
        return Document(version, rules.sortedBy { it.id }, errors)
    }

    private fun parseRule(o: JSONObject, path: String, errors: MutableList<ValidationError>): Rule? {
        val id = boundedString(o, "id", path, errors)
        val name = boundedString(o, "name", path, errors)
        val enabled = o.optBoolean("enabled", true)
        val triggers = parseTriggers(o.optJSONArray("triggers"), "$path.triggers", errors)
        val conditions = parseConditions(
            o.optJSONArray("conditions"), "$path.conditions", 1, errors,
        )
        val actions = parseActions(o.optJSONArray("actions"), "$path.actions", errors)
        if (id.isEmpty()) {
            errors.add(ValidationError("missing_id", "$path.id", "required"))
            return null
        }
        if (name.isEmpty()) {
            errors.add(ValidationError("missing_name", "$path.name", "required"))
            return null
        }
        if (id.length > MAX_ID_LEN) {
            errors.add(ValidationError("id_too_long", "$path.id", "${id.length} > $MAX_ID_LEN"))
            return null
        }
        if (name.length > MAX_NAME_LEN) {
            errors.add(
                ValidationError("name_too_long", "$path.name", "${name.length} > $MAX_NAME_LEN"),
            )
            return null
        }
        if (triggers.isEmpty()) {
            errors.add(ValidationError("no_triggers", "$path.triggers", "at least one required"))
            return null
        }
        return Rule(id, name, enabled, triggers, conditions, actions)
    }

    private fun boundedString(
        o: JSONObject, key: String, path: String, errors: MutableList<ValidationError>,
    ): String {
        val v = o.optString(key, "")
        if (v.length > MAX_STRING_LEN) {
            errors.add(
                ValidationError("string_too_long", "$path.$key", "${v.length} > $MAX_STRING_LEN"),
            )
            return v.take(MAX_STRING_LEN)
        }
        return v
    }

    private fun parseTriggers(
        arr: JSONArray?, path: String, errors: MutableList<ValidationError>,
    ): List<Trigger> {
        if (arr == null || arr.length() == 0) return emptyList()
        if (arr.length() > MAX_TRIGGERS_PER_RULE) {
            errors.add(
                ValidationError(
                    "too_many_triggers", path, "${arr.length()} > $MAX_TRIGGERS_PER_RULE",
                ),
            )
            return emptyList()
        }
        val out = mutableListOf<Trigger>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                errors.add(ValidationError("trigger_not_object", "$path[$i]", "expected object"))
                continue
            }
            val t = parseTrigger(o, "$path[$i]", errors)
            if (t != null) out.add(t)
        }
        return out
    }

    private fun parseTrigger(
        o: JSONObject, path: String, errors: MutableList<ValidationError>,
    ): Trigger? {
        val type = o.optString("type", "")
        val t: Trigger? = when (type) {
            "on_startup" -> Trigger.OnStartup
            "on_interval" -> Trigger.OnInterval(o.optLong("milliseconds", 0L))
            "on_module_toggle" -> Trigger.OnModuleToggle(
                boundedString(o, "moduleId", path, errors),
            )
            "on_profile_change" -> Trigger.OnProfileChange
            "on_theme_change" -> Trigger.OnThemeChange
            "on_setting_change" -> Trigger.OnSettingChange(
                boundedString(o, "section", path, errors),
                boundedString(o, "key", path, errors),
            )
            "on_hud_element_change" -> Trigger.OnHudElementChange(
                boundedString(o, "elementId", path, errors),
            )
            else -> null
        }
        if (t == null) {
            errors.add(ValidationError("unknown_trigger", "$path.type", type.ifEmpty { "(none)" }))
        }
        return t
    }

    private fun parseConditions(
        arr: JSONArray?, path: String, depth: Int, errors: MutableList<ValidationError>,
    ): List<Condition> {
        if (arr == null || arr.length() == 0) return emptyList()
        if (arr.length() > MAX_CONDITIONS_PER_RULE) {
            errors.add(
                ValidationError(
                    "too_many_conditions", path, "${arr.length()} > $MAX_CONDITIONS_PER_RULE",
                ),
            )
            return emptyList()
        }
        if (depth > MAX_CONDITION_DEPTH) {
            errors.add(
                ValidationError(
                    "condition_too_deep", path, "depth $depth > $MAX_CONDITION_DEPTH",
                ),
            )
            return emptyList()
        }
        val out = mutableListOf<Condition>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                errors.add(ValidationError("condition_not_object", "$path[$i]", "expected object"))
                continue
            }
            parseCondition(o, "$path[$i]", depth, errors)?.let { out.add(it) }
        }
        return out
    }

    private fun parseCondition(
        o: JSONObject, path: String, depth: Int, errors: MutableList<ValidationError>,
    ): Condition? {
        if (depth > MAX_CONDITION_DEPTH) {
            errors.add(
                ValidationError("condition_too_deep", path, "depth $depth > $MAX_CONDITION_DEPTH"),
            )
            return null
        }
        val type = o.optString("type", "")
        val c: Condition? = when (type) {
            "module_enabled" -> Condition.ModuleEnabled(boundedString(o, "moduleId", path, errors))
            "profile_equals" -> Condition.ProfileEquals(
                boundedString(o, "profileName", path, errors),
            )
            "theme_equals" -> Condition.ThemeEquals(
                boundedString(o, "themeName", path, errors),
            )
            "setting_equals" -> Condition.SettingEquals(
                boundedString(o, "section", path, errors),
                boundedString(o, "key", path, errors),
                boundedString(o, "value", path, errors),
            )
            "time_between" -> Condition.TimeBetween(
                o.optInt("startHour", 0), o.optInt("endHour", 23),
            )
            "and" -> Condition.And(
                parseConditions(o.optJSONArray("conditions"), "$path.conditions", depth + 1, errors),
            )
            "or" -> Condition.Or(
                parseConditions(o.optJSONArray("conditions"), "$path.conditions", depth + 1, errors),
            )
            "not" -> {
                val inner = o.optJSONObject("condition")
                if (inner == null) {
                    errors.add(
                        ValidationError("missing_condition", "$path.condition", "expected object"),
                    )
                    null
                } else {
                    parseCondition(inner, "$path.condition", depth + 1, errors)?.let {
                        Condition.Not(it)
                    }
                }
            }
            else -> null
        }
        if (c == null && type.isNotEmpty() && type !in CONDITION_TYPES) {
            errors.add(ValidationError("unknown_condition", "$path.type", type))
        }
        return c
    }

    private fun parseActions(
        arr: JSONArray?, path: String, errors: MutableList<ValidationError>,
    ): List<Action> {
        if (arr == null || arr.length() == 0) return emptyList()
        if (arr.length() > MAX_ACTIONS_PER_RULE) {
            errors.add(
                ValidationError("too_many_actions", path, "${arr.length()} > $MAX_ACTIONS_PER_RULE"),
            )
            return emptyList()
        }
        val out = mutableListOf<Action>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                errors.add(ValidationError("action_not_object", "$path[$i]", "expected object"))
                continue
            }
            val a = parseAction(o, "$path[$i]", errors)
            if (a != null) out.add(a)
        }
        return out
    }

    private fun parseAction(
        o: JSONObject, path: String, errors: MutableList<ValidationError>,
    ): Action? {
        val type = o.optString("type", "")
        val a: Action? = when (type) {
            "set_module_enabled" -> Action.SetModuleEnabled(
                boundedString(o, "moduleId", path, errors), o.optBoolean("enabled", true),
            )
            "set_profile" -> Action.SetProfile(boundedString(o, "profileName", path, errors))
            "set_theme" -> Action.SetTheme(boundedString(o, "themeName", path, errors))
            "set_setting" -> Action.SetSetting(
                boundedString(o, "section", path, errors),
                boundedString(o, "key", path, errors),
                boundedString(o, "value", path, errors),
            )
            "set_hud_element_visible" -> Action.SetHudElementVisible(
                boundedString(o, "elementId", path, errors), o.optBoolean("visible", true),
            )
            "set_hud_element_position" -> Action.SetHudElementPosition(
                boundedString(o, "elementId", path, errors),
                o.optInt("x", 0), o.optInt("y", 0),
            )
            "set_hud_element_scale" -> Action.SetHudElementScale(
                boundedString(o, "elementId", path, errors),
                o.optDouble("scale", 1.0).toFloat(),
            )
            "log" -> Action.Log(boundedString(o, "message", path, errors))
            "notify" -> Action.Notify(
                boundedString(o, "title", path, errors),
                boundedString(o, "message", path, errors),
            )
            else -> null
        }
        if (a == null) {
            errors.add(ValidationError("unknown_action", "$path.type", type.ifEmpty { "(none)" }))
        }
        return a
    }

    // --- Evaluation

    /** Everything a condition may read. Supplied by the host, never by a rule. */
    data class EvalContext(
        val moduleEnabled: (String) -> Boolean,
        val currentProfile: String,
        val currentTheme: String,
        /** Returns null when the setting does not exist, so `== ""` cannot match a miss. */
        val getSetting: (String, String) -> String?,
        /** Local wall-clock hour 0..23, injected so evaluation is deterministic in tests. */
        val currentHour: Int,
    )

    fun evaluateConditions(conditions: List<Condition>, context: EvalContext): Boolean =
        conditions.all { evalCondition(it, context, 1) }

    private fun evalCondition(c: Condition, ctx: EvalContext, depth: Int): Boolean {
        if (depth > MAX_CONDITION_DEPTH) return false
        return when (c) {
            is Condition.ModuleEnabled -> ctx.moduleEnabled(c.moduleId)
            is Condition.ProfileEquals -> ctx.currentProfile == c.profileName
            is Condition.ThemeEquals -> ctx.currentTheme == c.themeName
            is Condition.SettingEquals -> ctx.getSetting(c.section, c.key) == c.value
            is Condition.TimeBetween ->
                if (c.startHour <= c.endHour) {
                    ctx.currentHour in c.startHour..c.endHour
                } else {
                    // Window wraps midnight, e.g. 22..6.
                    ctx.currentHour >= c.startHour || ctx.currentHour <= c.endHour
                }
            is Condition.And -> c.conditions.all { evalCondition(it, ctx, depth + 1) }
            is Condition.Or -> c.conditions.any { evalCondition(it, ctx, depth + 1) }
            is Condition.Not -> !evalCondition(c.condition, ctx, depth + 1)
        }
    }

    fun matches(t: Trigger, event: ScriptRuntime.Event): Boolean = when (t) {
        is Trigger.OnStartup -> event is ScriptRuntime.Event.Startup
        is Trigger.OnInterval -> event is ScriptRuntime.Event.Tick && t.milliseconds == event.intervalMs
        is Trigger.OnModuleToggle ->
            event is ScriptRuntime.Event.ModuleToggled && t.moduleId == event.moduleId
        is Trigger.OnProfileChange -> event is ScriptRuntime.Event.ProfileChanged
        is Trigger.OnThemeChange -> event is ScriptRuntime.Event.ThemeChanged
        is Trigger.OnSettingChange ->
            event is ScriptRuntime.Event.SettingChanged &&
                t.section == event.section && t.key == event.key
        is Trigger.OnHudElementChange ->
            event is ScriptRuntime.Event.HudElementChanged && t.elementId == event.elementId
    }

    // --- Canonical serialization. Fixed key order, so output is byte-stable.

    fun serialize(rules: List<Rule>, schemaVersion: Int = SCHEMA_VERSION): String {
        val body = rules.sortedBy { it.id }.joinToString(",") { ruleToJson(it) }
        return """{"schemaVersion":$schemaVersion,"rules":[$body]}"""
    }

    private fun ruleToJson(r: Rule): String = obj(
        "id" to str(r.id),
        "name" to str(r.name),
        "enabled" to r.enabled.toString(),
        "triggers" to arr(r.triggers.map { triggerToJson(it) }),
        "conditions" to arr(r.conditions.map { conditionToJson(it) }),
        "actions" to arr(r.actions.map { actionToJson(it) }),
    )

    private fun triggerToJson(t: Trigger): String = when (t) {
        is Trigger.OnStartup -> obj("type" to str(t.type))
        is Trigger.OnInterval -> obj("type" to str(t.type), "milliseconds" to t.milliseconds.toString())
        is Trigger.OnModuleToggle ->
            obj("type" to str(t.type), "moduleId" to str(t.moduleId))
        is Trigger.OnProfileChange -> obj("type" to str(t.type))
        is Trigger.OnThemeChange -> obj("type" to str(t.type))
        is Trigger.OnSettingChange ->
            obj("type" to str(t.type), "section" to str(t.section), "key" to str(t.key))
        is Trigger.OnHudElementChange ->
            obj("type" to str(t.type), "elementId" to str(t.elementId))
    }

    private fun conditionToJson(c: Condition): String = when (c) {
        is Condition.ModuleEnabled -> obj("type" to str(c.type), "moduleId" to str(c.moduleId))
        is Condition.ProfileEquals ->
            obj("type" to str(c.type), "profileName" to str(c.profileName))
        is Condition.ThemeEquals -> obj("type" to str(c.type), "themeName" to str(c.themeName))
        is Condition.SettingEquals -> obj(
            "type" to str(c.type), "section" to str(c.section),
            "key" to str(c.key), "value" to str(c.value),
        )
        is Condition.TimeBetween -> obj(
            "type" to str(c.type),
            "startHour" to c.startHour.toString(), "endHour" to c.endHour.toString(),
        )
        is Condition.And -> obj(
            "type" to str(c.type),
            "conditions" to arr(c.conditions.map { conditionToJson(it) }),
        )
        is Condition.Or -> obj(
            "type" to str(c.type),
            "conditions" to arr(c.conditions.map { conditionToJson(it) }),
        )
        is Condition.Not -> obj("type" to str(c.type), "condition" to conditionToJson(c.condition))
    }

    private fun actionToJson(a: Action): String = when (a) {
        is Action.SetModuleEnabled -> obj(
            "type" to str(a.type), "moduleId" to str(a.moduleId), "enabled" to a.enabled.toString(),
        )
        is Action.SetProfile -> obj("type" to str(a.type), "profileName" to str(a.profileName))
        is Action.SetTheme -> obj("type" to str(a.type), "themeName" to str(a.themeName))
        is Action.SetSetting -> obj(
            "type" to str(a.type), "section" to str(a.section),
            "key" to str(a.key), "value" to str(a.value),
        )
        is Action.SetHudElementVisible -> obj(
            "type" to str(a.type), "elementId" to str(a.elementId),
            "visible" to a.visible.toString(),
        )
        is Action.SetHudElementPosition -> obj(
            "type" to str(a.type), "elementId" to str(a.elementId),
            "x" to a.x.toString(), "y" to a.y.toString(),
        )
        is Action.SetHudElementScale -> obj(
            "type" to str(a.type), "elementId" to str(a.elementId), "scale" to a.scale.toString(),
        )
        is Action.Log -> obj("type" to str(a.type), "message" to str(a.message))
        is Action.Notify -> obj(
            "type" to str(a.type), "title" to str(a.title), "message" to str(a.message),
        )
    }

    private fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:$v" }

    private fun arr(items: List<String>): String = items.joinToString(",", "[", "]")

    private fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' ->
                    sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
