package dev.xykell.client.runtime.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScriptRuntimeTest {

    private lateinit var api: FakeScriptApi
    private lateinit var clock: FakeClock
    private lateinit var store: CountingStore

    @Before
    fun setUp() {
        api = FakeScriptApi().apply {
            settings["client/theme"] = "Xykell Dark"
            settings["hud/fps"] = "true"
            modules["fps"] = false
            modules["cps"] = false
            hud["fps"] = HudElementState("fps", true, 16, 48, 1.0f)
            unknownProfile = "ghost"
            unknownTheme = "Ghost"
            unknownModule = "ghost_module"
        }
        clock = FakeClock()
        store = CountingStore()
    }

    private fun runtime(
        sandbox: ScriptSandbox = ScriptSandbox(clock = clock),
        st: CountingStore = store,
    ) = ScriptRuntime(sandbox, api, st, clock)

    // --- Lifecycle

    @Test
    fun startsStoppedAndStartsOnce() {
        val rt = runtime()
        assertFalse(rt.isRunning)
        rt.start()
        assertTrue(rt.isRunning)
        rt.start() // idempotent
        assertTrue(rt.isRunning)
    }

    @Test
    fun dispatchIgnoredWhileStopped() {
        val rt = runtime()
        rt.install(listOf(rule(actions = listOf(ScriptEngine.Action.Log("x")))))
        val r = rt.dispatch(ScriptRuntime.Event.Startup)
        assertEquals(0, r.matched)
        assertEquals(0, r.executed)
    }

    @Test
    fun stopHaltsDispatchAndClearsBudget() {
        val rt = runtime()
        rt.start()
        rt.stop()
        assertFalse(rt.isRunning)
        assertEquals(0, rt.dispatch(ScriptRuntime.Event.Startup).matched)
    }

    @Test
    fun startLoadsPersistedRules() {
        store = CountingStore(ScriptEngine.serialize(listOf(rule(id = "persisted"))))
        val rt = runtime()
        rt.start()
        assertEquals(listOf("persisted"), rt.rules().map { it.id })
    }

    @Test
    fun startIgnoresInvalidPersistedRules() {
        val bad = rule(
            id = "bad",
            actions = listOf(ScriptEngine.Action.SetSetting("secrets", "t", "v")),
        )
        store = CountingStore(ScriptEngine.serialize(listOf(bad)))
        val rt = runtime()
        rt.start()
        assertTrue(rt.rules().isEmpty())
    }

    @Test
    fun resetClearsRulesAndPersists() {
        val rt = runtime()
        rt.start()
        rt.install(listOf(rule()))
        assertTrue(rt.reset())
        assertTrue(rt.rules().isEmpty())
        assertFalse(rt.isRunning)
        assertEquals("{\"schemaVersion\":2,\"rules\":[]}", store.lastWritten)
    }

    // --- Trigger dispatch

    @Test
    fun startupTriggerFiresOnStartupOnly() {
        val rt = runtime()
        rt.start()
        rt.install(
            listOf(
                rule(
                    id = "startup_rule",
                    triggers = listOf(ScriptEngine.Trigger.OnStartup),
                    actions = listOf(ScriptEngine.Action.Log("boot")),
                ),
            ),
        )
        assertEquals(1, rt.dispatch(ScriptRuntime.Event.Startup).executed)
        assertEquals(0, rt.dispatch(ScriptRuntime.Event.ThemeChanged).executed)
    }

    @Test
    fun moduleToggleTriggerMatchesOnlyThatModule() {
        val rt = runtime()
        rt.start()
        rt.install(
            listOf(
                rule(
                    id = "toggle_fps",
                    triggers = listOf(ScriptEngine.Trigger.OnModuleToggle("fps")),
                    actions = listOf(ScriptEngine.Action.SetModuleEnabled("fps", true)),
                ),
            ),
        )
        assertEquals(0, rt.dispatch(ScriptRuntime.Event.ModuleToggled("cps", true)).executed)
        assertEquals(1, rt.dispatch(ScriptRuntime.Event.ModuleToggled("fps", true)).executed)
        assertTrue(api.modules["fps"] == true)
    }

    @Test
    fun settingChangedTriggerMatchesSectionAndKey() {
        val rt = runtime()
        rt.start()
        rt.install(
            listOf(
                rule(
                    id = "sc",
                    triggers = listOf(ScriptEngine.Trigger.OnSettingChange("client", "theme")),
                    actions = listOf(ScriptEngine.Action.Log("hit")),
                ),
            ),
        )
        assertEquals(0, rt.dispatch(ScriptRuntime.Event.SettingChanged("client", "fps")).executed)
        assertEquals(1, rt.dispatch(ScriptRuntime.Event.SettingChanged("client", "theme")).executed)
    }

    @Test
    fun intervalTriggerMatchesItsOwnPeriod() {
        val rt = runtime()
        rt.start()
        rt.install(
            listOf(
                rule(
                    id = "iv",
                    triggers = listOf(ScriptEngine.Trigger.OnInterval(5_000L)),
                    actions = listOf(ScriptEngine.Action.Log("tick")),
                ),
            ),
        )
        assertEquals(0, rt.dispatch(ScriptRuntime.Event.Tick(1_000L)).executed)
        assertEquals(1, rt.dispatch(ScriptRuntime.Event.Tick(5_000L)).executed)
    }

    @Test
    fun disabledRuleMatchedButNotExecuted() {
        val rt = runtime()
        rt.start()
        rt.install(listOf(rule(id = "off", enabled = false)))
        val r = rt.dispatch(ScriptRuntime.Event.Startup)
        assertEquals(1, r.matched)
        assertEquals(0, r.executed)
        assertEquals(1, r.skippedDisabled)
    }

    @Test
    fun setEnabledTogglesAndPersists() {
        val rt = runtime()
        rt.start()
        rt.install(listOf(rule(id = "r", enabled = true)))
        assertTrue(rt.setEnabled("r", false))
        assertFalse(rt.rules().first().enabled)
        assertFalse(rt.setEnabled("nope", false))
    }

    // --- Conditions

    @Test
    fun conditionGatesAction() {
        val rt = runtime()
        rt.start()
        rt.install(
            listOf(
                rule(
                    id = "gated",
                    conditions = listOf(ScriptEngine.Condition.ModuleEnabled("fps")),
                    actions = listOf(ScriptEngine.Action.SetModuleEnabled("fps", true)),
                ),
            ),
        )
        val blocked = rt.dispatch(ScriptRuntime.Event.Startup)
        assertEquals(0, blocked.executed)
        assertEquals(1, blocked.skippedConditions)
        assertFalse(api.modules["fps"] == true)
        api.modules["fps"] = true
        clock.advance(1_000)
        assertEquals(1, rt.dispatch(ScriptRuntime.Event.Startup).executed)
        assertTrue(api.modules["fps"] == true)
    }

    @Test
    fun settingConditionDistinguishesMissingFromEmpty() {
        val rt = runtime()
        rt.start()
        api.settings["client/empty"] = ""
        rt.install(
            listOf(
                rule(
                    id = "missing_key",
                    conditions = listOf(
                        ScriptEngine.Condition.SettingEquals("client", "absent", ""),
                    ),
                    actions = listOf(ScriptEngine.Action.Log("should not run")),
                ),
            ),
        )
        assertEquals(0, rt.dispatch(ScriptRuntime.Event.Startup).executed)
    }

    @Test
    fun timeBetweenUsesInjectedClock() {
        val rt = runtime()
        rt.start()
        rt.install(
            listOf(
                rule(
                    id = "day",
                    conditions = listOf(ScriptEngine.Condition.TimeBetween(9, 17)),
                    actions = listOf(ScriptEngine.Action.Log("work hours")),
                ),
            ),
        )
        // 1970-01-01T02:00Z rendered in the default zone is hour 2 or later;
        // assert against the runtime's own hourOfDay so the test is zone-proof.
        val hour = ScriptRuntime.hourOfDay(clock.now)
        val inside = hour in 9..17
        assertEquals(if (inside) 1 else 0, rt.dispatch(ScriptRuntime.Event.Startup).executed)
    }

    // --- Action execution

    @Test
    fun everyActionTypeRoutesToTheApi() {
        val rt = runtime()
        rt.start()
        api.theme = "Xykell Dark"
        rt.install(
            listOf(
                rule(
                    id = "all_actions",
                    triggers = listOf(ScriptEngine.Trigger.OnStartup),
                    actions = listOf(
                        ScriptEngine.Action.SetModuleEnabled("fps", true),
                        ScriptEngine.Action.SetProfile("Default"),
                        ScriptEngine.Action.SetTheme("Xykell Dark"),
                        ScriptEngine.Action.SetSetting("client", "theme", "Xykell Dark"),
                        ScriptEngine.Action.SetHudElementVisible("fps", false),
                        ScriptEngine.Action.SetHudElementPosition("fps", 40, 60),
                        ScriptEngine.Action.SetHudElementScale("fps", 2.0f),
                        ScriptEngine.Action.Log("hello"),
                        ScriptEngine.Action.Notify("Title", "Body"),
                    ),
                ),
            ),
        )
        val r = rt.dispatch(ScriptRuntime.Event.Startup)
        assertEquals(1, r.executed)
        assertEquals(0, r.failed)
        assertTrue(api.modules["fps"] == true)
        assertEquals(false, api.hud["fps"]!!.visible)
        assertEquals(40, api.hud["fps"]!!.x)
        assertEquals(2.0f, api.hud["fps"]!!.scale, 0.001f)
        assertEquals(1, api.diagnostics.size)
        assertEquals(1, api.notifications.size)
    }

    @Test
    fun refusedActionIsRecordedNotSwallowed() {
        val rt = runtime()
        rt.start()
        rt.install(
            listOf(
                rule(
                    id = "ghost",
                    actions = listOf(ScriptEngine.Action.SetModuleEnabled("ghost_module", true)),
                ),
            ),
        )
        val r = rt.dispatch(ScriptRuntime.Event.Startup)
        assertEquals(0, r.executed)
        assertNotNull(rt.status().first().lastError)
    }

    @Test
    fun sandboxRefusesOversizedRuleAtInstall() {
        val rt = runtime(
            sandbox = ScriptSandbox(
                ScriptSandbox.Limits(maxActionsPerExecution = 2), clock = clock,
            ),
        )
        rt.start()
        val rejected = rt.install(
            listOf(
                rule(
                    id = "many",
                    actions = listOf(
                        ScriptEngine.Action.Log("a"),
                        ScriptEngine.Action.Log("b"),
                        ScriptEngine.Action.Log("c"),
                    ),
                ),
            ),
        )
        assertEquals(1, rejected.size)
        assertTrue(rt.rules().isEmpty())
    }

    @Test
    fun runtimeHardCapStopsAnOversizedRun() {
        // Sandbox is permissive here; the runtime's own cap must still bite.
        val rt = ScriptRuntime(
            ScriptSandbox(ScriptSandbox.Limits(maxActionsPerExecution = 64), clock = clock),
            api,
            ScriptRuntime.MemoryStore(),
            clock,
            maxActionsPerRun = 2,
        )
        rt.start()
        rt.install(
            listOf(
                rule(
                    id = "many",
                    actions = listOf(
                        ScriptEngine.Action.Log("a"),
                        ScriptEngine.Action.Log("b"),
                        ScriptEngine.Action.Log("c"),
                    ),
                ),
            ),
        )
        val r = rt.dispatch(ScriptRuntime.Event.Startup)
        assertEquals(0, r.executed)
        assertEquals(1, r.failed)
        assertEquals(2, api.diagnostics.size)
        assertTrue(rt.status().first().lastError!!.contains("budget"))
    }

    // --- Budget / recursion

    @Test
    fun perRuleCooldownPreventsRapidRerun() {
        val rt = runtime()
        rt.start()
        rt.install(listOf(rule(id = "once", actions = listOf(ScriptEngine.Action.Log("x")))))
        assertEquals(1, rt.dispatch(ScriptRuntime.Event.Startup).executed)
        assertEquals(0, rt.dispatch(ScriptRuntime.Event.Startup).executed)
        clock.advance(1_000)
        assertEquals(1, rt.dispatch(ScriptRuntime.Event.Startup).executed)
    }

    @Test
    fun ruleThatReDispatchesItselfCannotRecurse() {
        // A rule whose action re-dispatches the runtime: the re-entrancy guard
        // must refuse the nested dispatch instead of ping-ponging forever.
        val holder = arrayOfNulls<ScriptRuntime>(1)
        var nestedRuns = 0
        val reentrant = object : ScriptApi by api {
            override fun emitDiagnostic(event: String, detail: String): ApiResult<Unit> {
                holder[0]?.let { nestedRuns += it.dispatch(ScriptRuntime.Event.Startup).executed }
                return ApiResult.Ok(Unit)
            }
        }
        val rt = ScriptRuntime(
            ScriptSandbox(clock = clock), reentrant, ScriptRuntime.MemoryStore(), clock,
        )
        holder[0] = rt
        rt.start()
        rt.install(listOf(rule(id = "loop", actions = listOf(ScriptEngine.Action.Log("again")))))
        assertEquals(1, rt.dispatch(ScriptRuntime.Event.Startup).executed)
        assertEquals(0, nestedRuns)
    }

    // --- Failure isolation

    @Test
    fun oneBrokenRuleDoesNotStopTheOthers() {
        val rt = runtime()
        rt.start()
        rt.install(
            listOf(
                rule(id = "a_fine", actions = listOf(ScriptEngine.Action.Log("ok"))),
                rule(id = "b_broken", actions = listOf(ScriptEngine.Action.Log("boom"))),
                rule(id = "c_fine", actions = listOf(ScriptEngine.Action.Log("ok2"))),
            ),
        )
        val good = CountingStore()
        val g = ScriptRuntime(
            ScriptSandbox(clock = clock), api, good, clock,
        )
        g.start()
        g.install(
            listOf(
                rule(id = "a_fine", actions = listOf(ScriptEngine.Action.Log("ok"))),
                rule(id = "b_broken", actions = listOf(ScriptEngine.Action.Log("boom"))),
                rule(id = "c_fine", actions = listOf(ScriptEngine.Action.Log("ok2"))),
            ),
        )
        // api.failEverything makes every call fail; the runtime must not throw.
        api.failEverything = true
        val r = g.dispatch(ScriptRuntime.Event.Startup)
        assertEquals(3, r.matched)
        assertEquals(0, r.executed)
        assertEquals(3, r.failed)
        api.failEverything = false
        assertTrue(rt.isRunning)
    }

    @Test
    fun runtimeSurvivesThrowingAction() {
        val exploding = object : ScriptApi by api {
            override fun emitDiagnostic(event: String, detail: String): ApiResult<Unit> =
                throw IllegalStateException("boom")
        }
        val rt = ScriptRuntime(
            ScriptSandbox(clock = clock), exploding, ScriptRuntime.MemoryStore(), clock,
        )
        rt.start()
        rt.install(listOf(rule(id = "x", actions = listOf(ScriptEngine.Action.Log("y")))))
        val r = rt.dispatch(ScriptRuntime.Event.Startup)
        assertEquals(1, r.matched)
        assertEquals(0, r.executed)
        assertEquals(1, r.failed)
    }

    // --- Determinism

    @Test
    fun executionOrderFollowsRuleIdNotFileOrder() {
        val rt = runtime()
        rt.start()
        rt.install(
            listOf(
                rule(id = "z_last", actions = listOf(ScriptEngine.Action.Log("z"))),
                rule(id = "a_first", actions = listOf(ScriptEngine.Action.Log("a"))),
            ),
        )
        assertEquals(listOf("a_first", "z_last"), rt.rules().map { it.id })
        rt.dispatch(ScriptRuntime.Event.Startup)
        assertEquals(listOf("a", "z"), api.diagnostics.map { it.second })
    }

    @Test
    fun serializationIsByteStableRegardlessOfInsertionOrder() {
        val a = ScriptEngine.serialize(
            listOf(rule(id = "b"), rule(id = "a")),
        )
        val b = ScriptEngine.serialize(
            listOf(rule(id = "a"), rule(id = "b")),
        )
        assertEquals(a, b)
    }

    // --- Persistence, import/export

    @Test
    fun malformedPersistedDocumentIsIgnoredNotFatal() {
        store = CountingStore("{not json at all")
        val rt = runtime()
        rt.start()
        assertTrue(rt.isRunning)
        assertTrue(rt.rules().isEmpty())
    }

    @Test
    fun v1DocumentMigratesForward() {
        // v1 had no schemaVersion and no explicit enabled flag.
        store = CountingStore(
            """{"rules":[{"id":"legacy","name":"Legacy","triggers":[{"type":"on_startup"}],
               "actions":[{"type":"log","message":"hi"}]}]}""",
        )
        val rt = runtime()
        rt.start()
        assertEquals(1, rt.rules().size)
        assertTrue(rt.rules().first().enabled) // defaulted true
        assertTrue(ScriptEngine.serialize(rt.rules()).contains("\"schemaVersion\":2"))
    }

    @Test
    fun futureSchemaVersionIsRefused() {
        store = CountingStore("""{"schemaVersion":99,"rules":[]}""")
        val rt = runtime()
        rt.start()
        assertTrue(rt.rules().isEmpty())
    }

    @Test
    fun exportContainsNoSecretsOrCode() {
        val rt = runtime()
        rt.start()
        rt.install(
            listOf(
                rule(
                    id = "e",
                    actions = listOf(
                        ScriptEngine.Action.SetSetting("client", "theme", "Xykell Dark"),
                    ),
                ),
            ),
        )
        val doc = rt.exportDocument()
        assertTrue(doc.contains("set_setting"))
        assertFalse(doc.contains("token"))
        assertFalse(doc.contains("exec"))
        assertFalse(doc.contains("class"))
    }

    @Test
    fun importMergesAndNeverOverwritesExistingIds() {
        val rt = runtime()
        rt.start()
        rt.install(listOf(rule(id = "shared", name = "Local")))
        val incoming = ScriptEngine.serialize(
            listOf(
                rule(id = "shared", name = "Incoming"),
                rule(id = "fresh", name = "Fresh"),
            ),
        )
        val res = rt.importDocument(incoming)
        assertTrue(res is ApiResult.Ok)
        assertEquals(listOf("fresh"), (res as ApiResult.Ok).value.map { it.id })
        assertEquals("Local", rt.rules().first { it.id == "shared" }.name)
    }

    @Test
    fun importRejectsInvalidDocument() {
        val rt = runtime()
        rt.start()
        assertTrue(rt.importDocument("{oops") is ApiResult.Invalid)
    }

    @Test
    fun duplicateCreatesDisabledCopyAndRefusesCollision() {
        val rt = runtime()
        rt.start()
        rt.install(listOf(rule(id = "orig")))
        val res = rt.duplicate("orig", "copy", "Copy")
        assertTrue(res is ApiResult.Ok)
        assertFalse((res as ApiResult.Ok).value.enabled)
        assertTrue(rt.duplicate("orig", "orig", "Again") is ApiResult.Invalid)
        assertTrue(rt.duplicate("missing", "x", "X") is ApiResult.Invalid)
    }

    @Test
    fun deleteRemovesRuleAndItsDiagnostics() {
        val rt = runtime()
        rt.start()
        rt.install(listOf(rule(id = "gone")))
        assertTrue(rt.delete("gone"))
        assertTrue(rt.rules().isEmpty())
        assertFalse(rt.delete("gone"))
    }

    @Test
    fun upsertRejectsInvalidRuleWithoutMutating() {
        val rt = runtime()
        rt.start()
        val v = rt.upsert(
            rule(id = "nope", actions = listOf(ScriptEngine.Action.SetSetting("root", "k", "v"))),
        )
        assertTrue(v.isNotEmpty())
        assertTrue(rt.rules().isEmpty())
    }

    @Test
    fun installRefusesOversizedRuleSet() {
        val rt = runtime(
            sandbox = ScriptSandbox(ScriptSandbox.Limits(maxRules = 1), clock = clock),
        )
        rt.start()
        val rejected = rt.install(listOf(rule(id = "a"), rule(id = "b")))
        assertEquals(1, rejected.size)
        assertTrue(rt.rules().isEmpty())
    }

    @Test
    fun diagnosticsReportCountsAndBudget() {
        val rt = runtime()
        rt.start()
        rt.install(listOf(rule(id = "d", actions = listOf(ScriptEngine.Action.Log("x")))))
        rt.dispatch(ScriptRuntime.Event.Startup)
        val d = rt.diagnostics()
        assertEquals(1, d.scriptExecutions)
        assertEquals(0, d.scriptFailures)
        assertNull(d.lastError)
    }

    @Test
    fun hourOfDayIsInRange() {
        for (ms in listOf(0L, 1L, 86_400_000L, 1_700_000_000_000L)) {
            val h = ScriptRuntime.hourOfDay(ms)
            assertTrue("hour $h for $ms", h in 0..23)
        }
    }
}
