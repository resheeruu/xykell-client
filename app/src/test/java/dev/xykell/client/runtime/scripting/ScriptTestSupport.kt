package dev.xykell.client.runtime.scripting

/**
 * Test doubles for the scripting subsystem. Every fake here records what it was
 * asked to do so tests can assert on effects, and every one of them refuses
 * anything the real backends refuse.
 */
class FakeScriptApi : ScriptApi {

    val settings = linkedMapOf<String, String>()
    val modules = linkedMapOf<String, Boolean>()
    val hud = linkedMapOf<String, HudElementState>()
    var profile: String = "Default"
    var theme: String = "Xykell Dark"
    val notifications = mutableListOf<Pair<String, String>>()
    val diagnostics = mutableListOf<Pair<String, String>>()

    /** Ids the fake refuses, mirroring the real backends' allowlists. */
    val unknownHudIds = mutableSetOf("not_an_element")
    var unknownProfile = "__none__"
    var unknownTheme = "__none__"
    var unknownModule = "__none__"

    /** When set, every call returns Failed, to exercise failure isolation. */
    var failEverything = false

    override val capabilities: Set<ScriptCapability> = ScriptCapability.values().toSet()

    private fun <T> guard(ok: T): ApiResult<T> =
        if (failEverything) ApiResult.Failed("injected failure") else ApiResult.Ok(ok)

    override fun activeProfile(): ApiResult<String> = guard(profile)
    override fun switchProfile(name: String): ApiResult<Unit> {
        if (failEverything) return ApiResult.Failed("injected failure")
        if (name == unknownProfile) return ApiResult.Invalid("unknown profile $name")
        profile = name
        return ApiResult.Ok(Unit)
    }
    override fun resetProfile(): ApiResult<Unit> =
        ApiResult.Unavailable("reset is manual")

    override fun readSetting(section: String, key: String): ApiResult<String> {
        if (failEverything) return ApiResult.Failed("injected failure")
        val v = settings["$section/$key"] ?: return ApiResult.Invalid("no $section/$key")
        return ApiResult.Ok(v)
    }
    override fun writeSetting(section: String, key: String, value: String): ApiResult<Unit> {
        if (failEverything) return ApiResult.Failed("injected failure")
        settings["$section/$key"] = value
        return ApiResult.Ok(Unit)
    }

    override fun readHudElement(elementId: String): ApiResult<HudElementState> {
        if (failEverything) return ApiResult.Failed("injected failure")
        if (elementId in unknownHudIds) return ApiResult.Invalid("unknown HUD element")
        val s = hud[elementId] ?: return ApiResult.Invalid("element not in layout")
        return ApiResult.Ok(s)
    }
    override fun setHudElementVisible(elementId: String, visible: Boolean): ApiResult<Unit> =
        mutate(elementId) { it.copy(visible = visible) }
    override fun setHudElementPosition(elementId: String, x: Int, y: Int): ApiResult<Unit> =
        mutate(elementId) { it.copy(x = x, y = y) }
    override fun setHudElementScale(elementId: String, scale: Float): ApiResult<Unit> =
        mutate(elementId) { it.copy(scale = scale) }

    private fun mutate(
        elementId: String, block: (HudElementState) -> HudElementState,
    ): ApiResult<Unit> {
        if (failEverything) return ApiResult.Failed("injected failure")
        if (elementId in unknownHudIds) return ApiResult.Invalid("unknown HUD element")
        val cur = hud[elementId] ?: return ApiResult.Invalid("element not in layout")
        hud[elementId] = block(cur)
        return ApiResult.Ok(Unit)
    }

    override fun activeTheme(): ApiResult<String> = guard(theme)
    override fun switchTheme(themeName: String): ApiResult<String> {
        if (failEverything) return ApiResult.Failed("injected failure")
        if (themeName == unknownTheme) return ApiResult.Invalid("unknown theme")
        theme = themeName
        return ApiResult.Ok(themeName)
    }

    override fun isModuleEnabled(moduleId: String): ApiResult<Boolean> {
        if (failEverything) return ApiResult.Failed("injected failure")
        val v = modules[moduleId] ?: return ApiResult.Invalid("unknown module")
        return ApiResult.Ok(v)
    }
    override fun setModuleEnabled(moduleId: String, enabled: Boolean): ApiResult<Unit> {
        if (failEverything) return ApiResult.Failed("injected failure")
        if (!modules.containsKey(moduleId)) return ApiResult.Invalid("unknown module")
        modules[moduleId] = enabled
        return ApiResult.Ok(Unit)
    }

    override fun emitDiagnostic(event: String, detail: String): ApiResult<Unit> {
        if (failEverything) return ApiResult.Failed("injected failure")
        diagnostics.add(event to detail)
        return ApiResult.Ok(Unit)
    }
    override fun readDiagnostics(): ApiResult<Diagnostics> = ApiResult.Ok(
        Diagnostics(diagnostics.size, 0, -1, diagnostics.lastOrNull()?.second),
    )
    override fun readSession(): ApiResult<SessionState> = ApiResult.Ok(
        SessionState(profile, theme, modules.count { it.value }, true),
    )
    override fun notify(title: String, message: String): ApiResult<Unit> {
        if (failEverything) return ApiResult.Failed("injected failure")
        notifications.add(title to message)
        return ApiResult.Ok(Unit)
    }
}

/** Mutable clock so budget and cooldown behaviour is deterministic. */
class FakeClock(var now: Long = 1_000_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/** Counts how many times a rule document is written. */
class CountingStore(initial: String = "") : ScriptRuntime.RuleStore {
    var writes = 0
    var lastWritten = initial
    private var doc = initial
    override fun read(): String = doc
    override fun write(document: String): Boolean {
        writes++
        lastWritten = document
        doc = document
        return true
    }
}

fun rule(
    id: String = "r1",
    name: String = "Rule",
    enabled: Boolean = true,
    triggers: List<ScriptEngine.Trigger> = listOf(ScriptEngine.Trigger.OnStartup),
    conditions: List<ScriptEngine.Condition> = emptyList(),
    actions: List<ScriptEngine.Action> = listOf(
        ScriptEngine.Action.SetSetting("client", "theme", "Xykell Dark"),
    ),
): ScriptEngine.Rule = ScriptEngine.Rule(id, name, enabled, triggers, conditions, actions)
