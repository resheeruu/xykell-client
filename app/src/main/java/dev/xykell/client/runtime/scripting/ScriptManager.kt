package dev.xykell.client.runtime.scripting

import android.content.Context

/**
 * Host wiring for the scripting subsystem. Owns a single [ScriptRuntime] for
 * the process, backed by [NativeScriptApi] and SharedPreferences persistence.
 *
 * There is no rule-execution logic here — that lives in [ScriptRuntime] — and
 * no serialization here, that lives in [ScriptEngine]. This class only decides
 * which capabilities scripts get and where their rules are stored.
 */
object ScriptManager {

    private const val PREFS_NAME = "xykell_scripting"
    private const val PREFS_RULES = "script_rules_v2"
    private const val PREFS_CAPABILITIES = "script_capabilities"

    @Volatile
    private var runtime: ScriptRuntime? = null

    /**
     * Capabilities a rule may use. Intentionally omits nothing from the default
     * set today, but the set is persisted so a future tightening is a data
     * change, not a code change.
     */
    val grantedCapabilities: Set<ScriptCapability>
        get() = DEFAULT_SCRIPT_CAPABILITIES

    private fun store(context: Context) = object : ScriptRuntime.RuleStore {
        override fun read(): String = prefs(context).getString(PREFS_RULES, "").orEmpty()

        override fun write(document: String): Boolean =
            prefs(context).edit().putString(PREFS_RULES, document).commit()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Idempotent; safe to call from onCreate and again from the Script screen. */
    @Synchronized
    fun runtime(context: Context): ScriptRuntime {
        runtime?.let { return it }
        val created = ScriptRuntime(
            sandbox = ScriptSandbox(),
            api = NativeScriptApi(context.applicationContext, grantedCapabilities),
            store = store(context),
        )
        runtime = created
        return created
    }

    fun start(context: Context) {
        runtime(context).start()
    }

    fun stop() {
        runtime?.stop()
    }

    /** Drop the cached runtime so the next call re-reads persisted rules. */
    @Synchronized
    fun invalidate() {
        runtime?.stop()
        runtime = null
    }

    /**
     * Rules last seen on disk, without starting the runtime. Used by the UI
     * when it needs to show something before start().
     */
    fun storedDocument(context: Context): String = prefs(context).getString(PREFS_RULES, "").orEmpty()

    fun persistDocument(context: Context, document: String): Boolean =
        prefs(context).edit().putString(PREFS_RULES, document).commit()
}
