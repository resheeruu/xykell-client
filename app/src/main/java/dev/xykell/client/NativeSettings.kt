package dev.xykell.client

import android.content.Context
import java.io.File

/**
 * JNI facade over the native settings catalog (Batch 7). Confined to the
 * settings domain: catalog snapshot, current values, validated set, scoped
 * reset. NOT a generic JSON bridge. All calls are exception-safe: native
 * failure surfaces as false/empty, never a crash.
 */
object NativeSettings {
    init {
        System.loadLibrary("xykellcore")
    }

    fun root(context: Context): String =
        File(context.filesDir, "xykell").absolutePath

    external fun settingsCatalog(): String
    external fun settingsValues(root: String): String
    external fun setSetting(root: String, section: String, key: String, valueJson: String): Boolean
    external fun resetSettings(root: String, section: String): Boolean

    fun getCatalog(): String = guard { settingsCatalog() } ?: "{}"
    fun getValues(root: String): String = guard { settingsValues(root) } ?: "{}"

    fun set(root: String, section: String, key: String, valueJson: String): Boolean =
        guard { setSetting(root, section, key, valueJson) } ?: false

    /** Empty section resets everything. */
    fun reset(root: String, section: String = ""): Boolean =
        guard { resetSettings(root, section) } ?: false

    /**
     * Turns an UnsatisfiedLinkError into the documented fallback, but records it
     * first. A missing symbol must show up in diagnostics rather than making
     * this bridge look like it simply had no data.
     */
    private const val BRIDGE = "NativeSettings"

    private inline fun <T> guard(symbol: String = "", block: () -> T): T? {
        return try {
            val v = block()
            dev.xykell.client.runtime.NativeBridgeStatus.recordSuccess(
                BRIDGE, if (symbol.isEmpty()) "call" else symbol,
            )
            v
        } catch (e: UnsatisfiedLinkError) {
            dev.xykell.client.runtime.NativeBridgeStatus.recordFailure(
                BRIDGE, if (symbol.isEmpty()) "call" else symbol,
            )
            null
        }
    }
}
