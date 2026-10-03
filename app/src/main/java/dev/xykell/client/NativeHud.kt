package dev.xykell.client

/** JNI facade over profile HUD layouts + module flags (Batch 8).
 *  Confined to HUD/profile editing: validated layout JSON, bounded
 *  element edits, module enable flags. NOT a generic bridge. All calls
 *  exception-safe: failure surfaces as false/empty, never a crash. */
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

    private inline fun <T> guard(block: () -> T): T? {
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
}
