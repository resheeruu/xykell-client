package dev.xykell.client.runtime.scripting

/**
 * Sandbox for the declarative rule engine: what a rule is allowed to contain
 * and how often it is allowed to run.
 *
 * The engine already refuses unknown trigger/condition/action types. The
 * sandbox adds the policy layer the engine deliberately does not know about:
 * identifier shape, value bounds, section allowlist, rate limits and a
 * re-entrancy guard. It holds no state except a budget counter, so it is
 * trivially testable on the host JVM.
 */
class ScriptSandbox(
    val limits: Limits = Limits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Hard policy ceilings. Tightening them is always safe. */
    data class Limits(
        val maxRules: Int = 200,
        val maxConditionDepth: Int = ScriptEngine.MAX_CONDITION_DEPTH,
        val maxActionsPerExecution: Int = 32,
        val minIntervalMs: Long = 1_000L,
        /** Rolling window for the execution budget. */
        val budgetWindowMs: Long = 60_000L,
        val budgetPerWindow: Int = 120,
        /** Per-rule cooldown after it fires, independent of the global budget. */
        val perRuleCooldownMs: Long = 250L,
        val maxHudCoordinate: Int = 10_000,
        val minHudScale: Float = 0.25f,
        val maxHudScale: Float = 4.0f,
        val maxValueLength: Int = ScriptEngine.MAX_STRING_LEN,
    )

    data class Violation(val code: String, val path: String, val detail: String) {
        override fun toString() = "$code at $path: $detail"
    }

    /**
     * Config sections a rule may read or write. Mirrors the top-level sections
     * in native/src/xykell_config_store.cpp `defaults()`. Anything else is
     * refused rather than passed to the store.
     */
    val allowedSections: Set<String> = setOf(
        "client", "modules", "hud", "gui", "rendering", "input", "network", "profile",
    )

    private val idPattern = Regex("^[a-z][a-z0-9_]{0,63}$")

    // --- Rolling execution budget + per-rule cooldown.

    private val timestamps = ArrayDeque<Long>()
    private val lastRun = HashMap<String, Long>()

    /**
     * True when [ruleId] may execute now, recording the execution when allowed.
     * Set [useCooldown] false for the dispatch gate itself: a dispatch is not a
     * rule, and rate-limiting back-to-back dispatches with a per-rule cooldown
     * would make every burst after the first a silent no-op.
     */
    @Synchronized
    fun tryAcquire(ruleId: String, useCooldown: Boolean = true): Boolean {
        val now = clock()
        while (timestamps.isNotEmpty() && now - timestamps.first() >= limits.budgetWindowMs) {
            timestamps.removeFirst()
        }
        if (useCooldown) {
            val last = lastRun[ruleId]
            if (last != null && now - last < limits.perRuleCooldownMs) return false
        }
        if (timestamps.size >= limits.budgetPerWindow) return false
        timestamps.addLast(now)
        if (useCooldown) lastRun[ruleId] = now
        return true
    }

    @Synchronized
    fun budgetRemaining(): Int = (limits.budgetPerWindow - timestamps.size).coerceAtLeast(0)

    @Synchronized
    fun resetBudget() {
        timestamps.clear()
        lastRun.clear()
    }

    // --- Static rule validation.

    fun validate(rule: ScriptEngine.Rule): List<Violation> {
        val v = mutableListOf<Violation>()
        if (!idPattern.matches(rule.id)) {
            v.add(Violation("bad_rule_id", "id", rule.id.take(64)))
        }
        if (rule.name.isBlank()) v.add(Violation("blank_name", "name", ""))
        if (rule.actions.isEmpty()) {
            v.add(Violation("no_actions", "actions", "rule would do nothing"))
        }
        if (rule.triggers.isEmpty()) v.add(Violation("no_triggers", "triggers", ""))
        if (rule.actions.size > limits.maxActionsPerExecution) {
            v.add(
                Violation(
                    "too_many_actions", "actions",
                    "${rule.actions.size} > ${limits.maxActionsPerExecution}",
                ),
            )
        }
        for (t in rule.triggers) validateTrigger(t, v)
        var deepest = 0
        for (c in rule.conditions) {
            deepest = maxOf(deepest, conditionDepth(c, 1))
            validateCondition(c, "conditions", v)
        }
        if (deepest > limits.maxConditionDepth) {
            v.add(
                Violation("condition_too_deep", "conditions", "$deepest > ${limits.maxConditionDepth}"),
            )
        }
        for ((i, a) in rule.actions.withIndex()) validateAction(a, "actions[$i]", v)
        return v
    }

    fun validateAll(rules: List<ScriptEngine.Rule>): List<Violation> {
        val v = mutableListOf<Violation>()
        if (rules.size > limits.maxRules) {
            v.add(Violation("too_many_rules", "rules", "${rules.size} > ${limits.maxRules}"))
        }
        val ids = mutableSetOf<String>()
        for (r in rules) {
            if (!ids.add(r.id)) v.add(Violation("duplicate_rule_id", "rules", r.id))
            v.addAll(validate(r))
        }
        return v
    }

    private fun conditionDepth(c: ScriptEngine.Condition, depth: Int): Int = when (c) {
        is ScriptEngine.Condition.And ->
            c.conditions.maxOfOrNull { conditionDepth(it, depth + 1) } ?: depth
        is ScriptEngine.Condition.Or ->
            c.conditions.maxOfOrNull { conditionDepth(it, depth + 1) } ?: depth
        is ScriptEngine.Condition.Not -> conditionDepth(c.condition, depth + 1)
        else -> depth
    }

    private fun validateTrigger(t: ScriptEngine.Trigger, v: MutableList<Violation>) {
        if (t.type !in ScriptEngine.TRIGGER_TYPES) {
            v.add(Violation("trigger_not_allowed", "triggers", t.type))
            return
        }
        when (t) {
            is ScriptEngine.Trigger.OnInterval ->
                if (t.milliseconds < limits.minIntervalMs) {
                    v.add(
                        Violation(
                            "interval_too_short", "triggers.milliseconds",
                            "${t.milliseconds} < ${limits.minIntervalMs}",
                        ),
                    )
                }
            is ScriptEngine.Trigger.OnModuleToggle ->
                if (!idPattern.matches(t.moduleId)) {
                    v.add(Violation("bad_module_id", "triggers.moduleId", t.moduleId.take(64)))
                }
            is ScriptEngine.Trigger.OnSettingChange -> {
                checkSection(t.section, "triggers.section", v)
                if (!idPattern.matches(t.key)) {
                    v.add(Violation("bad_setting_key", "triggers.key", t.key.take(64)))
                }
            }
            is ScriptEngine.Trigger.OnHudElementChange ->
                if (!idPattern.matches(t.elementId)) {
                    v.add(Violation("bad_hud_element_id", "triggers.elementId", t.elementId.take(64)))
                }
            else -> Unit
        }
    }

    private fun validateCondition(
        c: ScriptEngine.Condition, path: String, v: MutableList<Violation>,
    ) {
        if (c.type !in ScriptEngine.CONDITION_TYPES) {
            v.add(Violation("condition_not_allowed", path, c.type))
            return
        }
        when (c) {
            is ScriptEngine.Condition.ModuleEnabled ->
                if (!idPattern.matches(c.moduleId)) {
                    v.add(Violation("bad_module_id", "$path.moduleId", c.moduleId.take(64)))
                }
            is ScriptEngine.Condition.SettingEquals -> {
                checkSection(c.section, "$path.section", v)
                if (!idPattern.matches(c.key)) {
                    v.add(Violation("bad_setting_key", "$path.key", c.key.take(64)))
                }
                checkValue(c.value, "$path.value", v)
            }
            is ScriptEngine.Condition.TimeBetween -> {
                if (c.startHour !in 0..23 || c.endHour !in 0..23) {
                    v.add(Violation("hour_out_of_range", path, "${c.startHour}..${c.endHour}"))
                }
            }
            is ScriptEngine.Condition.And -> c.conditions.forEachIndexed { i, inner ->
                validateCondition(inner, "$path.conditions[$i]", v)
            }
            is ScriptEngine.Condition.Or -> c.conditions.forEachIndexed { i, inner ->
                validateCondition(inner, "$path.conditions[$i]", v)
            }
            is ScriptEngine.Condition.Not -> validateCondition(c.condition, "$path.condition", v)
            else -> Unit
        }
    }

    private fun validateAction(
        a: ScriptEngine.Action, path: String, v: MutableList<Violation>,
    ) {
        if (a.type !in ScriptEngine.ACTION_TYPES) {
            v.add(Violation("action_not_allowed", path, a.type))
            return
        }
        when (a) {
            is ScriptEngine.Action.SetModuleEnabled ->
                if (!idPattern.matches(a.moduleId)) {
                    v.add(Violation("bad_module_id", "$path.moduleId", a.moduleId.take(64)))
                }
            is ScriptEngine.Action.SetProfile ->
                checkName(a.profileName, "$path.profileName", v)
            is ScriptEngine.Action.SetTheme ->
                checkName(a.themeName, "$path.themeName", v)
            is ScriptEngine.Action.SetSetting -> {
                checkSection(a.section, "$path.section", v)
                if (!idPattern.matches(a.key)) {
                    v.add(Violation("bad_setting_key", "$path.key", a.key.take(64)))
                }
                checkValue(a.value, "$path.value", v)
            }
            is ScriptEngine.Action.SetHudElementVisible ->
                if (!idPattern.matches(a.elementId)) {
                    v.add(Violation("bad_hud_element_id", "$path.elementId", a.elementId.take(64)))
                }
            is ScriptEngine.Action.SetHudElementPosition -> {
                if (!idPattern.matches(a.elementId)) {
                    v.add(Violation("bad_hud_element_id", "$path.elementId", a.elementId.take(64)))
                }
                for ((axis, n) in listOf("x" to a.x, "y" to a.y)) {
                    if (n < -limits.maxHudCoordinate || n > limits.maxHudCoordinate) {
                        v.add(
                            Violation(
                                "coordinate_out_of_range", "$path.$axis",
                                "$n outside +/-${limits.maxHudCoordinate}",
                            ),
                        )
                    }
                }
            }
            is ScriptEngine.Action.SetHudElementScale -> {
                if (!idPattern.matches(a.elementId)) {
                    v.add(Violation("bad_hud_element_id", "$path.elementId", a.elementId.take(64)))
                }
                if (a.scale < limits.minHudScale || a.scale > limits.maxHudScale ||
                    a.scale.isNaN() || a.scale.isInfinite()
                ) {
                    v.add(
                        Violation(
                            "scale_out_of_range", "$path.scale",
                            "${a.scale} outside ${limits.minHudScale}..${limits.maxHudScale}",
                        ),
                    )
                }
            }
            is ScriptEngine.Action.Log -> checkValue(a.message, "$path.message", v)
            is ScriptEngine.Action.Notify -> {
                checkValue(a.title, "$path.title", v)
                checkValue(a.message, "$path.message", v)
            }
        }
    }

    private fun checkSection(section: String, path: String, v: MutableList<Violation>) {
        if (section !in allowedSections) {
            v.add(Violation("section_not_allowed", path, section.take(64)))
        }
    }

    private fun checkValue(value: String, path: String, v: MutableList<Violation>) {
        if (value.length > limits.maxValueLength) {
            v.add(
                Violation("value_too_long", path, "${value.length} > ${limits.maxValueLength}"),
            )
        }
        // Rules are local data; refuse anything that looks like a control/injection
        // vector before it can reach a log, a notification, or a config file.
        if (value.any { it.code < 0x20 || it.code == 0x7f }) {
            v.add(Violation("control_character_in_value", path, "rejected"))
        }
    }

    private fun checkName(name: String, path: String, v: MutableList<Violation>) {
        if (name.isBlank()) {
            v.add(Violation("blank_name", path, ""))
            return
        }
        if (name.length > limits.maxValueLength) {
            v.add(Violation("value_too_long", path, "${name.length} > ${limits.maxValueLength}"))
            return
        }
        if (name.any { it.code < 0x20 || it.code == 0x7f || it == '/' || it == '\\' }) {
            v.add(Violation("illegal_character_in_name", path, "rejected"))
        }
    }
}
