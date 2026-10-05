package dev.xykell.client.runtime.scripting

import android.content.Context
import android.widget.Toast
import dev.xykell.client.NativeHud
import dev.xykell.client.NativeSettings
import dev.xykell.client.runtime.ProfileManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ScriptApi] backed by the real Xykell subsystems. This is the only class in
 * the scripting package that touches Android or JNI, and it touches nothing
 * but the profile store, the config store and the HUD layout.
 *
 * Read paths are cached per dispatch by the caller; this class parses the
 * config JSON once per call, which is bounded by the sandbox action budget.
 */
class NativeScriptApi(
    private val context: Context,
    override val capabilities: Set<ScriptCapability> = DEFAULT_SCRIPT_CAPABILITIES,
) : ScriptApi {

    /**
     * HUD element ids accepted by this API. Mirrors `ElementType` in
     * native/include/xykell/hud_model.h; `typeFromName` refuses anything else,
     * so this list is an explicit allowlist rather than a pass-through.
     */
    private val hudElementIds = setOf(
        "watermark", "fps", "coordinates", "cps", "module_list", "notifications",
        "armor", "health", "hunger", "keystrokes", "target_hud",
    )

    private val configSections = setOf(
        "client", "modules", "hud", "gui", "rendering", "input", "network", "profile",
    )

    private val diag = ArrayDeque<String>()

    private fun root() = NativeSettings.root(context)

    private fun values(): JSONObject? =
        try { JSONObject(NativeSettings.getValues(root())) } catch (_: Exception) { null }

    private fun note(line: String) {
        synchronized(diag) {
            diag.addLast("${System.currentTimeMillis()} $line")
            while (diag.size > MAX_DIAG_LINES) diag.removeFirst()
        }
    }

    // PROFILE

    override fun activeProfile(): ApiResult<String> =
        ApiResult.Ok(ProfileManager.currentProfile)

    override fun switchProfile(name: String): ApiResult<Unit> {
        if (name.isBlank()) return ApiResult.Invalid("blank profile name")
        val known = ProfileManager.profiles
        if (name !in known) return ApiResult.Invalid("unknown profile $name")
        return if (ProfileManager.select(name)) {
            ApiResult.Ok(Unit)
        } else {
            ApiResult.Failed("select returned false")
        }
    }

    override fun resetProfile(): ApiResult<Unit> = ApiResult.Unavailable(
        "profile reset is a manual settings action; scripts may switch but not wipe",
    )

    // SETTINGS

    override fun readSetting(section: String, key: String): ApiResult<String> {
        if (section !in configSections) return ApiResult.Invalid("section $section")
        val obj = values()?.optJSONObject(section) ?: return ApiResult.Invalid("no section")
        if (!obj.has(key)) return ApiResult.Invalid("no key $section/$key")
        return ApiResult.Ok(obj.opt(key).toString())
    }

    override fun writeSetting(section: String, key: String, value: String): ApiResult<Unit> {
        if (section !in configSections) return ApiResult.Invalid("section $section")
        val encoded = when (value) {
            "true", "false" -> value
            else -> JSONObject.quote(value)
        }
        return if (NativeSettings.set(root(), section, key, encoded)) {
            ApiResult.Ok(Unit)
        } else {
            ApiResult.Failed("config store rejected $section/$key")
        }
    }

    // HUD

    private fun layout(): JSONArray? {
        val raw = NativeHud.layout(root(), ProfileManager.currentProfile)
        if (raw.isBlank()) return null
        return try {
            JSONObject(raw).optJSONArray("elements")
        } catch (_: Exception) {
            null
        }
    }

    private fun indexOf(elementId: String): Int? {
        val els = layout() ?: return null
        for (i in 0 until els.length()) {
            if (els.optJSONObject(i)?.optString("type") == elementId) return i
        }
        return null
    }

    private fun stateOf(i: Int): HudElementState? {
        val e = layout()?.optJSONObject(i) ?: return null
        return HudElementState(
            elementId = e.optString("type"),
            visible = e.optBoolean("visible", true),
            x = e.optDouble("x", 0.0).toInt(),
            y = e.optDouble("y", 0.0).toInt(),
            scale = e.optDouble("scale", 1.0).toFloat(),
        )
    }

    override fun readHudElement(elementId: String): ApiResult<HudElementState> {
        if (elementId !in hudElementIds) return ApiResult.Invalid("unknown HUD element")
        val i = indexOf(elementId) ?: return ApiResult.Invalid("element not in layout")
        return stateOf(i)?.let { ApiResult.Ok(it) } ?: ApiResult.Failed("unreadable element")
    }

    /** Applies a partial change to one HUD element, preserving the other fields. */
    private fun mutate(
        elementId: String,
        x: Int? = null, y: Int? = null, scale: Float? = null, visible: Boolean? = null,
    ): ApiResult<Unit> {
        if (elementId !in hudElementIds) return ApiResult.Invalid("unknown HUD element")
        val i = indexOf(elementId) ?: return ApiResult.Invalid("element not in layout")
        val s = stateOf(i) ?: return ApiResult.Failed("unreadable element")
        val ok = NativeHud.move(
            root(), ProfileManager.currentProfile, i,
            (x ?: s.x).toDouble(), (y ?: s.y).toDouble(), (scale ?: s.scale).toDouble(),
            visible ?: s.visible,
        )
        return if (ok) ApiResult.Ok(Unit) else ApiResult.Failed("NativeHud refused")
    }

    override fun setHudElementVisible(elementId: String, visible: Boolean): ApiResult<Unit> =
        mutate(elementId, visible = visible)

    override fun setHudElementPosition(elementId: String, x: Int, y: Int): ApiResult<Unit> =
        mutate(elementId, x = x, y = y)

    override fun setHudElementScale(elementId: String, scale: Float): ApiResult<Unit> =
        mutate(elementId, scale = scale)

    // THEME

    override fun activeTheme(): ApiResult<String> {
        val obj = values()?.optJSONObject("client") ?: return ApiResult.Invalid("no client section")
        if (!obj.has("theme")) return ApiResult.Invalid("no theme key")
        return ApiResult.Ok(obj.optString("theme"))
    }

    override fun switchTheme(themeName: String): ApiResult<String> {
        val known = dev.xykell.client.NativeThemes.names()
        if (known.isEmpty()) return ApiResult.Unavailable("no theme catalog")
        if (themeName !in known) return ApiResult.Invalid("unknown theme $themeName")
        return if (NativeSettings.set(root(), "client", "theme", JSONObject.quote(themeName))) {
            ApiResult.Ok(themeName)
        } else {
            ApiResult.Failed("config store rejected theme")
        }
    }

    // MODULES

    override fun isModuleEnabled(moduleId: String): ApiResult<Boolean> {
        val mods = values()?.optJSONObject("modules") ?: return ApiResult.Invalid("no modules")
        if (!mods.has(moduleId)) return ApiResult.Invalid("unknown module $moduleId")
        return ApiResult.Ok(mods.optBoolean(moduleId, false))
    }

    override fun setModuleEnabled(moduleId: String, enabled: Boolean): ApiResult<Unit> {
        val mods = values()?.optJSONObject("modules") ?: return ApiResult.Invalid("no modules")
        if (!mods.has(moduleId)) return ApiResult.Invalid("unknown module $moduleId")
        val ok = NativeHud.toggleModule(root(), ProfileManager.currentProfile, moduleId, enabled)
        val viaConfig = NativeSettings.set(root(), "modules", moduleId, enabled.toString())
        return if (ok || viaConfig) ApiResult.Ok(Unit) else ApiResult.Failed("module write failed")
    }

    // DIAGNOSTICS / SESSION / NOTIFY

    override fun emitDiagnostic(event: String, detail: String): ApiResult<Unit> {
        note("$event ${detail.take(120)}")
        return ApiResult.Ok(Unit)
    }

    override fun readDiagnostics(): ApiResult<Diagnostics> {
        val lines = synchronized(diag) { diag.toList() }
        return ApiResult.Ok(
            Diagnostics(
                scriptExecutions = lines.count { it.contains("script.log") },
                scriptFailures = lines.size,
                budgetRemaining = -1,
                lastError = lines.lastOrNull(),
            ),
        )
    }

    override fun readSession(): ApiResult<SessionState> {
        val mods = values()?.optJSONObject("modules")
        var enabled = 0
        mods?.keys()?.forEach { k -> if (mods.optBoolean(k, false)) enabled++ }
        return ApiResult.Ok(
            SessionState(
                activeProfile = ProfileManager.currentProfile,
                activeTheme = activeTheme().valueOrNull().orEmpty(),
                enabledModuleCount = enabled,
                running = true,
            ),
        )
    }

    override fun notify(title: String, message: String): ApiResult<Unit> = try {
        Toast.makeText(
            context, if (title.isBlank()) message.take(80) else "$title: ${message.take(80)}",
            Toast.LENGTH_SHORT,
        ).show()
        ApiResult.Ok(Unit)
    } catch (e: Exception) {
        ApiResult.Failed("toast failed: ${e.javaClass.simpleName}")
    }

    private companion object {
        const val MAX_DIAG_LINES = 100
    }
}
