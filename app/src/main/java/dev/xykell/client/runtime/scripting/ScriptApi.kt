package dev.xykell.client.runtime.scripting

/**
 * Typed, capability-gated surface the script runtime is allowed to touch.
 *
 * Every method is Xykell-owned state only. Deliberately absent, with no
 * indirection that could reach them:
 *
 *   - Minecraft command execution, packet read/write, raw sockets
 *   - authentication tokens, account secrets, credential material
 *   - arbitrary filesystem paths, shell, process execution
 *   - reflection, dynamic class loading, native arbitrary invocation
 *   - game memory, anti-cheat or ban-evasion controls
 *
 * There is no method that takes a path, a command, a token, or a class name,
 * so a rule cannot express any of the above even if a future parser were to
 * be fed hostile input.
 */
interface ScriptApi {

    /** Capabilities this instance will honour. */
    val capabilities: Set<ScriptCapability>

    // PROFILE
    fun activeProfile(): ApiResult<String>
    fun switchProfile(name: String): ApiResult<Unit>
    fun resetProfile(): ApiResult<Unit>

    // SETTINGS (section must be in ScriptSandbox.allowedSections)
    fun readSetting(section: String, key: String): ApiResult<String>
    fun writeSetting(section: String, key: String, value: String): ApiResult<Unit>

    // HUD
    fun readHudElement(elementId: String): ApiResult<HudElementState>
    fun setHudElementVisible(elementId: String, visible: Boolean): ApiResult<Unit>
    fun setHudElementPosition(elementId: String, x: Int, y: Int): ApiResult<Unit>
    fun setHudElementScale(elementId: String, scale: Float): ApiResult<Unit>

    // THEME
    fun activeTheme(): ApiResult<String>
    fun switchTheme(themeName: String): ApiResult<String>

    // MODULES
    fun isModuleEnabled(moduleId: String): ApiResult<Boolean>
    fun setModuleEnabled(moduleId: String, enabled: Boolean): ApiResult<Unit>

    // DIAGNOSTICS / SESSION / NOTIFICATION
    fun emitDiagnostic(event: String, detail: String): ApiResult<Unit>
    fun readDiagnostics(): ApiResult<Diagnostics>
    fun readSession(): ApiResult<SessionState>
    fun notify(title: String, message: String): ApiResult<Unit>
}

enum class ScriptCapability {
    PROFILE_READ, PROFILE_SWITCH, PROFILE_RESET,
    SETTINGS_READ, SETTINGS_WRITE,
    HUD_READ, HUD_WRITE,
    THEME_READ, THEME_SWITCH,
    MODULES_READ, MODULES_WRITE,
    DIAGNOSTICS_READ, DIAGNOSTICS_WRITE, SESSION_READ, NOTIFY,
}

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Denied(val capability: ScriptCapability, val detail: String) : ApiResult<Nothing>()
    data class Invalid(val detail: String) : ApiResult<Nothing>()
    data class Unavailable(val detail: String) : ApiResult<Nothing>()
    data class Failed(val detail: String) : ApiResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    fun valueOrNull(): T? = (this as? Ok)?.value
    fun errorText(): String? = when (this) {
        is Ok -> null
        is Denied -> "denied ${capability.name}: $detail"
        is Invalid -> "invalid: $detail"
        is Unavailable -> "unavailable: $detail"
        is Failed -> "failed: $detail"
    }
}

data class HudElementState(
    val elementId: String,
    val visible: Boolean,
    val x: Int,
    val y: Int,
    val scale: Float,
)

/** Local app health only. Never contains tokens, paths outside the sandbox, or game state. */
data class Diagnostics(
    val scriptExecutions: Int,
    val scriptFailures: Int,
    val budgetRemaining: Int,
    val lastError: String?,
)

data class SessionState(
    val activeProfile: String,
    val activeTheme: String,
    val enabledModuleCount: Int,
    val running: Boolean,
)

/**
 * Wraps a backing [ScriptApi] and refuses any call whose capability was not
 * granted. Keeps the permission check in one place instead of once per method.
 */
class GuardedScriptApi(
    private val delegate: ScriptApi,
    private val granted: Set<ScriptCapability>,
) : ScriptApi {

    override val capabilities: Set<ScriptCapability> get() = granted

    private fun <T> gate(cap: ScriptCapability, body: () -> ApiResult<T>): ApiResult<T> {
        if (cap !in granted) return ApiResult.Denied(cap, "not granted to scripts")
        return body()
    }

    override fun activeProfile() = gate(ScriptCapability.PROFILE_READ, delegate::activeProfile)
    override fun switchProfile(name: String) =
        gate(ScriptCapability.PROFILE_SWITCH) { delegate.switchProfile(name) }
    override fun resetProfile() = gate(ScriptCapability.PROFILE_RESET, delegate::resetProfile)

    override fun readSetting(section: String, key: String) =
        gate(ScriptCapability.SETTINGS_READ) { delegate.readSetting(section, key) }
    override fun writeSetting(section: String, key: String, value: String) =
        gate(ScriptCapability.SETTINGS_WRITE) { delegate.writeSetting(section, key, value) }

    override fun readHudElement(elementId: String) =
        gate(ScriptCapability.HUD_READ) { delegate.readHudElement(elementId) }
    override fun setHudElementVisible(elementId: String, visible: Boolean) =
        gate(ScriptCapability.HUD_WRITE) { delegate.setHudElementVisible(elementId, visible) }
    override fun setHudElementPosition(elementId: String, x: Int, y: Int) =
        gate(ScriptCapability.HUD_WRITE) { delegate.setHudElementPosition(elementId, x, y) }
    override fun setHudElementScale(elementId: String, scale: Float) =
        gate(ScriptCapability.HUD_WRITE) { delegate.setHudElementScale(elementId, scale) }

    override fun activeTheme() = gate(ScriptCapability.THEME_READ, delegate::activeTheme)
    override fun switchTheme(themeName: String) =
        gate(ScriptCapability.THEME_SWITCH) { delegate.switchTheme(themeName) }

    override fun isModuleEnabled(moduleId: String) =
        gate(ScriptCapability.MODULES_READ) { delegate.isModuleEnabled(moduleId) }
    override fun setModuleEnabled(moduleId: String, enabled: Boolean) =
        gate(ScriptCapability.MODULES_WRITE) { delegate.setModuleEnabled(moduleId, enabled) }

    override fun emitDiagnostic(event: String, detail: String) =
        gate(ScriptCapability.DIAGNOSTICS_WRITE) { delegate.emitDiagnostic(event, detail) }
    override fun readDiagnostics() =
        gate(ScriptCapability.DIAGNOSTICS_READ, delegate::readDiagnostics)
    override fun readSession() = gate(ScriptCapability.SESSION_READ, delegate::readSession)
    override fun notify(title: String, message: String) =
        gate(ScriptCapability.NOTIFY) { delegate.notify(title, message) }
}

/**
 * Stands in when no host is wired (unit tests, headless). Every call reports
 * [ApiResult.Unavailable] rather than pretending to succeed.
 */
object UnavailableScriptApi : ScriptApi {
    override val capabilities: Set<ScriptCapability> = emptySet()
    private fun <T> no(): ApiResult<T> = ApiResult.Unavailable("no host bound")
    override fun activeProfile() = no<String>()
    override fun switchProfile(name: String) = no<Unit>()
    override fun resetProfile() = no<Unit>()
    override fun readSetting(section: String, key: String) = no<String>()
    override fun writeSetting(section: String, key: String, value: String) = no<Unit>()
    override fun readHudElement(elementId: String) = no<HudElementState>()
    override fun setHudElementVisible(elementId: String, visible: Boolean) = no<Unit>()
    override fun setHudElementPosition(elementId: String, x: Int, y: Int) = no<Unit>()
    override fun setHudElementScale(elementId: String, scale: Float) = no<Unit>()
    override fun activeTheme() = no<String>()
    override fun switchTheme(themeName: String) = no<String>()
    override fun isModuleEnabled(moduleId: String) = no<Boolean>()
    override fun setModuleEnabled(moduleId: String, enabled: Boolean) = no<Unit>()
    override fun emitDiagnostic(event: String, detail: String) = no<Unit>()
    override fun readDiagnostics() = no<Diagnostics>()
    override fun readSession() = no<SessionState>()
    override fun notify(title: String, message: String) = no<Unit>()
}

/** Default grant set: everything the API defines. Host may narrow it. */
val DEFAULT_SCRIPT_CAPABILITIES: Set<ScriptCapability> = ScriptCapability.values().toSet()
