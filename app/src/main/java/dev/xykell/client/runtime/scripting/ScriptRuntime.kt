package dev.xykell.client.runtime.scripting

/**
 * The scripting runtime: lifecycle, trigger dispatch, budget enforcement,
 * per-rule failure isolation and diagnostics.
 *
 * Pure Kotlin on purpose. It holds a [ScriptSandbox] (policy), a [ScriptApi]
 * (the only way to touch app state) and a [RuleStore] (persistence), so the
 * whole thing is exercisable on the host JVM with no Android runtime.
 */
class ScriptRuntime(
    private val sandbox: ScriptSandbox,
    private val api: ScriptApi,
    private val store: RuleStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxActionsPerRun: Int = 32,
) {

    /** Events the runtime dispatches against. Produced by the host, never by a rule. */
    sealed class Event {
        object Startup : Event()
        data class Tick(val intervalMs: Long) : Event()
        data class ModuleToggled(val moduleId: String, val enabled: Boolean) : Event()
        object ProfileChanged : Event()
        object ThemeChanged : Event()
        data class SettingChanged(val section: String, val key: String) : Event()
        data class HudElementChanged(val elementId: String) : Event()
    }

    /** Where rules come from. Kept behind an interface so persistence is swappable. */
    interface RuleStore {
        fun read(): String
        fun write(document: String): Boolean
    }

    /** In-memory store; also the default when no host store is supplied. */
    class MemoryStore(initial: String = "") : RuleStore {
        private var doc = initial
        override fun read(): String = doc
        override fun write(document: String): Boolean {
            doc = document
            return true
        }
    }

    data class RuleStatus(
        val rule: ScriptEngine.Rule,
        val violations: List<ScriptSandbox.Violation>,
        val lastRunAt: Long,
        val lastError: String?,
        val runCount: Int,
    ) {
        val valid: Boolean get() = violations.isEmpty()
    }

    data class Report(
        val event: String,
        val matched: Int,
        val executed: Int,
        val skippedDisabled: Int,
        val skippedInvalid: Int,
        val skippedBudget: Int,
        val skippedConditions: Int,
        val failed: Int,
    ) {
        companion object { val NONE = Report("", 0, 0, 0, 0, 0, 0, 0) }
    }

    /** Outcome of running one matched rule. */
    private enum class RunOutcome { RAN, CONDITIONS_BLOCKED, REFUSED }

    private var running = false
    private var installed: List<ScriptEngine.Rule> = emptyList()
    private var lastErrors = HashMap<String, String>()
    private var lastRunAt = HashMap<String, Long>()
    private var runCounts = HashMap<String, Int>()
    private var executions = 0
    private var failures = 0

    /**
     * Re-entrancy guard. An action that changes app state can cause the host to
     * dispatch again; without this a rule pair could ping-pong forever.
     */
    private var dispatchDepth = 0

    // --- Lifecycle

    val isRunning: Boolean get() = running
    val dispatching: Boolean get() = dispatchDepth > 0

    /** Load persisted rules and start accepting events. Idempotent. */
    fun start(): Report {
        if (running) return Report.NONE
        val doc = ScriptEngine.parseDocument(store.read())
        installed = doc.rules.filter { sandbox.validate(it).isEmpty() }
        running = true
        return dispatch(Event.Startup)
    }

    fun stop() {
        running = false
        sandbox.resetBudget()
    }

    /** Clear runtime state and persisted rules. Used by the UI "reset" action. */
    fun reset(): Boolean {
        stop()
        installed = emptyList()
        lastErrors = HashMap()
        lastRunAt = HashMap()
        runCounts = HashMap()
        executions = 0
        failures = 0
        return store.write(ScriptEngine.serialize(emptyList()))
    }

    // --- Rule management

    fun rules(): List<ScriptEngine.Rule> = installed

    fun status(): List<RuleStatus> = installed.map {
        RuleStatus(
            it, sandbox.validate(it), lastRunAt[it.id] ?: 0L,
            lastErrors[it.id], runCounts[it.id] ?: 0,
        )
    }

    /** Replace the rule set. Rules with violations are refused. Returns the rejected ones. */
    fun install(rules: List<ScriptEngine.Rule>): List<ScriptEngine.Rule> {
        val accepted = mutableListOf<ScriptEngine.Rule>()
        val rejected = mutableListOf<ScriptEngine.Rule>()
        val ids = mutableSetOf<String>()
        for (r in rules.sortedBy { it.id }) {
            when {
                sandbox.validate(r).isNotEmpty() -> rejected.add(r)
                !ids.add(r.id) -> rejected.add(r)
                accepted.size >= sandbox.limits.maxRules -> rejected.add(r)
                else -> accepted.add(r)
            }
        }
        if (rejected.isEmpty()) {
            installed = accepted
            persist()
        } else {
            // Partial acceptance would leave the caller unsure what is live.
            installed = installed
        }
        return rejected
    }

    fun upsert(rule: ScriptEngine.Rule): List<ScriptSandbox.Violation> {
        val v = sandbox.validate(rule)
        if (v.isNotEmpty()) return v
        val existing = installed.filter { it.id == rule.id }
        val merged = (installed.filter { it.id != rule.id } + rule).sortedBy { it.id }
        if (merged.size > sandbox.limits.maxRules) {
            return listOf(
                ScriptSandbox.Violation(
                    "too_many_rules", "rules", "${merged.size} > ${sandbox.limits.maxRules}",
                ),
            )
        }
        if (existing.isEmpty() && sandbox.validateAll(merged).isNotEmpty()) {
            return listOf(ScriptSandbox.Violation("install_conflict", "rules", rule.id))
        }
        installed = merged
        persist()
        return emptyList()
    }

    fun setEnabled(ruleId: String, enabled: Boolean): Boolean {
        val idx = installed.indexOfFirst { it.id == ruleId }
        if (idx < 0) return false
        installed = installed.toMutableList().also {
            it[idx] = installed[idx].copy(enabled = enabled)
        }.sortedBy { it.id }
        return persist()
    }

    fun delete(ruleId: String): Boolean {
        if (installed.none { it.id == ruleId }) return false
        installed = installed.filter { it.id != ruleId }
        lastErrors.remove(ruleId)
        lastRunAt.remove(ruleId)
        runCounts.remove(ruleId)
        return persist()
    }

    /** Copy a rule under a fresh id. Refused if the new id already exists. */
    fun duplicate(ruleId: String, newId: String, newName: String): ApiResult<ScriptEngine.Rule> {
        val src = installed.firstOrNull { it.id == ruleId }
            ?: return ApiResult.Invalid("no such rule $ruleId")
        if (installed.any { it.id == newId }) return ApiResult.Invalid("id $newId exists")
        val copy = src.copy(id = newId, name = newName, enabled = false)
        val v = sandbox.validate(copy)
        if (v.isNotEmpty()) return ApiResult.Invalid(v.joinToString("; ") { it.toString() })
        installed = (installed + copy).sortedBy { it.id }
        if (!persist()) return ApiResult.Failed("persist failed")
        return ApiResult.Ok(copy)
    }

    // --- Persistence, import/export

    private fun persist(): Boolean =
        store.write(ScriptEngine.serialize(installed))

    /** Declarative rules only. Contains no code and no secrets by construction. */
    fun exportDocument(): String = ScriptEngine.serialize(installed)

    /**
     * Merge an exported document into the current set. Rules whose ids already
     * exist are skipped, not overwritten, so import cannot clobber local edits.
     */
    fun importDocument(json: String): ApiResult<List<ScriptEngine.Rule>> {
        val doc = ScriptEngine.parseDocument(json)
        if (!doc.ok) {
            return ApiResult.Invalid(doc.errors.joinToString("; ") { it.toString() })
        }
        val fresh = doc.rules.filter { r -> installed.none { it.id == r.id } }
        if (fresh.isEmpty()) return ApiResult.Ok(emptyList())
        val rejected = install(installed + fresh)
        if (rejected.isNotEmpty()) {
            return ApiResult.Invalid(rejected.joinToString("; ") { "rejected ${it.id}" })
        }
        return ApiResult.Ok(fresh)
    }

    // --- Dispatch

    fun dispatch(event: Event): Report {
        if (!running) return Report.NONE
        if (dispatchDepth > 0) return Report.NONE // re-entrancy refused
        if (!sandbox.tryAcquire("__dispatch__", useCooldown = false)) return Report.NONE
        dispatchDepth++
        var matched = 0
        var executed = 0
        var disabled = 0
        var invalid = 0
        var budgeted = 0
        var blockedByConditions = 0
        var failed = 0
        try {
            // installed is already sorted by id, so execution order is deterministic.
            for (rule in installed) {
                if (!rule.triggers.any { ScriptEngine.matches(it, event) }) continue
                matched++
                if (!rule.enabled) {
                    disabled++
                    continue
                }
                val violations = sandbox.validate(rule)
                if (violations.isNotEmpty()) {
                    invalid++
                    recordError(rule.id, violations.joinToString("; ") { it.toString() })
                    continue
                }
                if (!sandbox.tryAcquire(rule.id)) {
                    budgeted++
                    continue
                }
                try {
                    when (runActions(rule)) {
                        RunOutcome.RAN -> {
                            executed++
                            executions++
                            lastRunAt[rule.id] = clock()
                            runCounts[rule.id] = (runCounts[rule.id] ?: 0) + 1
                        }
                        RunOutcome.CONDITIONS_BLOCKED -> blockedByConditions++
                        RunOutcome.REFUSED -> failed++
                    }
                } catch (e: Exception) {
                    // One broken rule must never take the runtime down.
                    failed++
                    failures++
                    recordError(rule.id, e.javaClass.simpleName)
                }
            }
        } finally {
            dispatchDepth--
        }
        return Report(
            event.javaClass.simpleName, matched, executed, disabled, invalid, budgeted,
            blockedByConditions, failed,
        )
    }

    private fun recordError(ruleId: String, message: String) {
        failures++
        lastErrors[ruleId] = message
    }

    /**
     * Second line of defence: a hard cap on actions per run, independent of the
     * sandbox's per-rule limit, so a rule that somehow reached execution
     * oversized still cannot flood the app.
     */
    private fun runActions(rule: ScriptEngine.Rule): RunOutcome {
        val ctx = evalContext()
        if (!ScriptEngine.evaluateConditions(rule.conditions, ctx)) {
            return RunOutcome.CONDITIONS_BLOCKED
        }
        var allOk = true
        var n = 0
        for (action in rule.actions) {
            if (n >= maxActionsPerRun) {
                recordError(rule.id, "action budget exhausted")
                return RunOutcome.REFUSED
            }
            n++
            if (!perform(action)) {
                allOk = false
                lastErrors[rule.id] = "action ${action.type} refused"
            }
        }
        return if (allOk) RunOutcome.RAN else RunOutcome.REFUSED
    }

    private fun perform(action: ScriptEngine.Action): Boolean = when (action) {
        is ScriptEngine.Action.SetModuleEnabled ->
            api.setModuleEnabled(action.moduleId, action.enabled).isOk
        is ScriptEngine.Action.SetProfile -> api.switchProfile(action.profileName).isOk
        is ScriptEngine.Action.SetTheme -> api.switchTheme(action.themeName).isOk
        is ScriptEngine.Action.SetSetting ->
            api.writeSetting(action.section, action.key, action.value).isOk
        is ScriptEngine.Action.SetHudElementVisible ->
            api.setHudElementVisible(action.elementId, action.visible).isOk
        is ScriptEngine.Action.SetHudElementPosition ->
            api.setHudElementPosition(action.elementId, action.x, action.y).isOk
        is ScriptEngine.Action.SetHudElementScale ->
            api.setHudElementScale(action.elementId, action.scale).isOk
        is ScriptEngine.Action.Log -> api.emitDiagnostic("script.log", action.message).isOk
        is ScriptEngine.Action.Notify -> api.notify(action.title, action.message).isOk
    }

    /** Reads come from the API too, so a rule can never observe state the API hides. */
    private fun evalContext() = ScriptEngine.EvalContext(
        moduleEnabled = { api.isModuleEnabled(it).valueOrNull() ?: false },
        currentProfile = api.activeProfile().valueOrNull().orEmpty(),
        currentTheme = api.activeTheme().valueOrNull().orEmpty(),
        getSetting = { s, k -> api.readSetting(s, k).valueOrNull() },
        currentHour = hourOfDay(clock()),
    )

    fun diagnostics() = Diagnostics(
        scriptExecutions = executions,
        scriptFailures = failures,
        budgetRemaining = sandbox.budgetRemaining(),
        lastError = lastErrors.values.lastOrNull(),
    )

    companion object {
        /** Local hour 0..23 for [ScriptEvent]-style time conditions. */
        fun hourOfDay(epochMs: Long): Int {
            val offset = epochMs + java.util.TimeZone.getDefault().getOffset(epochMs)
            return (((offset / 3_600_000L) % 24L) + 24L).toInt() % 24
        }
    }
}
