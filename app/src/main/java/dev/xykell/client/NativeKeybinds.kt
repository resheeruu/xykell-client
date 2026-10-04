package dev.xykell.client

import org.json.JSONObject

/** JNI facade over the keybind store (Batch 13). Bounded: list actions,
 *  bind/unbind one slot, reset all — codes are abstract host codes only,
 *  no keystroke content crosses the boundary. Failure surfaces as the
 *  exact native error string (or "native bridge unavailable"), never a
 *  fabricated message. */
object NativeKeybinds {
    init {
        System.loadLibrary("xykellcore")
    }

    external fun keybindList(root: String): String?
    external fun keybindSet(root: String, action: String, slot: Int, code: Int): String?
    external fun keybindReset(root: String): String?

    data class Bind(
        val action: String,
        val primary: Int,
        val secondary: Int,
    )

    data class Bindings(
        val binds: List<Bind>,
        val loadError: String,
    )

    fun list(root: String): Bindings? {
        val raw = guard { keybindList(root) } ?: return null
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("binds")
            val out = ArrayList<Bind>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONObject(i)
                    out.add(
                        Bind(
                            action = row.optString("action"),
                            primary = row.optInt("primary", 0),
                            secondary = row.optInt("secondary", 0),
                        ),
                    )
                }
            }
            Bindings(out, o.optString("loadError"))
        } catch (e: Exception) {
            null
        }
    }

    /** Returns "" on success, else the exact native error. */
    fun set(root: String, action: String, slot: Int, code: Int): String =
        guard { keybindSet(root, action, slot, code) } ?: "native bridge unavailable"

    /** Returns "" on success, else the exact native error. */
    fun reset(root: String): String =
        guard { keybindReset(root) } ?: "native bridge unavailable"

    private inline fun <T> guard(block: () -> T): T? {
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
}
