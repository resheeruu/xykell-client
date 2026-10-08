package dev.xykell.client

/** JNI facade over profile HUD layouts + module flags (Batch 8).
 *  Confined to HUD/profile editing: validated layout JSON, bounded
 *  element edits, module enable flags. NOT a generic bridge. All calls
 *  exception-safe: failure surfaces as false/empty, never a crash. */
import dev.xykell.client.ui.HudLine
import dev.xykell.client.ui.HudOverlayLines

object NativeHud {
    init {
        System.loadLibrary("xykellcore")
    }

    external fun getHudLayout(root: String, profile: String): String?
    external fun setHudLayout(root: String, profile: String, layoutJson: String): Boolean
    external fun setHudElement(
        root: String,
        profile: String,
        index: Int,
        x: Double,
        y: Double,
        scale: Double,
        visible: Boolean,
    ): Boolean
    external fun resetHudLayout(root: String, profile: String): Boolean
    external fun setProfileModule(root: String, profile: String, id: String, enabled: Boolean): Boolean

    /**
     * Render the HUD in this process. Returns null when no layout could be
     * read, and the caller must then draw nothing rather than a made-up frame.
     */
    external fun renderHudLines(root: String, profile: String): String?

    fun lines(root: String, profile: String): List<HudLine> =
        guard("renderHudLines") { renderHudLines(root, profile) }
            ?.let { HudOverlayLines.parse(it) }
            ?: emptyList()

    fun layout(root: String, profile: String): String =
        guard { getHudLayout(root, profile) } ?: "{}"

    fun commitLayout(root: String, profile: String, layoutJson: String): Boolean =
        guard { setHudLayout(root, profile, layoutJson) } ?: false

    fun move(
        root: String,
        profile: String,
        index: Int,
        x: Double,
        y: Double,
        scale: Double,
        visible: Boolean,
    ): Boolean = guard { setHudElement(root, profile, index, x, y, scale, visible) } ?: false

    fun reset(root: String, profile: String): Boolean =
        guard { resetHudLayout(root, profile) } ?: false

    fun toggleModule(root: String, profile: String, id: String, enabled: Boolean): Boolean =
        guard { setProfileModule(root, profile, id, enabled) } ?: false

    /**
     * Turns an UnsatisfiedLinkError into the documented fallback, but records it
     * first. A missing symbol must show up in diagnostics rather than making
     * this bridge look like it simply had no data.
     */
    private const val BRIDGE = "NativeHud"

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
