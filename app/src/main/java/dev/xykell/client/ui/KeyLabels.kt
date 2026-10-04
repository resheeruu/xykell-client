package dev.xykell.client.ui

/**
 * Host-code labels for the keybind editor (Batch 13). Pure JVM: maps the
 * abstract host codes used by the native KeybindManager to readable
 * names — non-negative codes are Android keycodes as assigned by this
 * launcher host, codes <= TOUCH_BASE are touch-region codes per
 * xykell::input::kTouchBase. Codes only; this object never sees or
 * stores keystroke content.
 */
object KeyLabels {
    const val TOUCH_BASE = -1000

    fun label(code: Int): String {
        if (code == 0) return "—"
        if (code < 0) {
            return if (code <= TOUCH_BASE) "TOUCH_${TOUCH_BASE - code}" else "TOUCH_$code"
        }
        named(code)?.let { return it }
        if (code in 7..16) return ('0' + (code - 7)).toString()
        if (code in 29..54) return ('A' + (code - 29)).toString()
        return "KEY_$code"
    }

    private fun named(code: Int): String? = when (code) {
        3 -> "HOME"
        4 -> "BACK"
        19 -> "DPAD_UP"
        20 -> "DPAD_DOWN"
        21 -> "DPAD_LEFT"
        22 -> "DPAD_RIGHT"
        24 -> "VOL_UP"
        25 -> "VOL_DOWN"
        57 -> "ALT_L"
        58 -> "ALT_R"
        59 -> "SHIFT_L"
        60 -> "SHIFT_R"
        61 -> "TAB"
        62 -> "SPACE"
        66 -> "ENTER"
        67 -> "DEL"
        82 -> "MENU"
        85 -> "MEDIA_PLAY"
        86 -> "MEDIA_STOP"
        87 -> "MEDIA_NEXT"
        88 -> "MEDIA_PREV"
        92 -> "PAGE_UP"
        93 -> "PAGE_DOWN"
        111 -> "ESC"
        113 -> "CTRL_L"
        114 -> "CTRL_R"
        115 -> "CAPS_LOCK"
        123 -> "MOVE_END"
        124 -> "MOVE_HOME"
        in 131..142 -> "F" + (code - 130)
        else -> null
    }
}
