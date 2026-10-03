package dev.xykell.client.ui

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Applies a parsed theme to a live view tree (Batch 10). Views whose
 * colors came from the compiled palette are re-mapped token-by-token;
 * anything else (platform controls, user-applied colors) is untouched.
 * [ActiveTheme] tracks the palette currently on screen so switches
 * re-map from the right baseline. JVM-testable logic lives in
 * [ThemeColors]; this binder is the Android-only walker (device smoke).
 */
object ActiveTheme {

    @Volatile
    var colors: ThemeColors = ThemeColors.DEFAULT
        private set

    /** Re-theme an already-rendered tree from the palette it shows now. */
    fun apply(root: View, to: ThemeColors) {
        val from = colors
        bind(from, to, root)
        colors = to
    }

    /**
     * Fresh views are inflated from compiled resources == the DEFAULT
     * palette, no matter which theme is active. Call after a fragment
     * view is attached; idempotent when the active theme is DEFAULT.
     */
    fun bindFresh(root: View) {
        bind(DEFAULT_BASELINE, colors, root)
    }

    private val DEFAULT_BASELINE: ThemeColors = ThemeColors.DEFAULT

    private fun bind(from: ThemeColors, to: ThemeColors, node: View) {
        val bg = node.background
        if (bg is ColorDrawable) {
            for (role in ThemeColors.Role.values()) {
                val next = ThemeColors.resolveRole(role, from, to, bg.color)
                if (next != null) {
                    node.setBackgroundColor(next)
                    break
                }
            }
        }
        if (node is TextView) {
            for (role in ThemeColors.Role.values()) {
                val next = ThemeColors.resolveRole(role, from, to, node.currentTextColor)
                if (next != null) {
                    node.setTextColor(next)
                    break
                }
            }
        }
        if (node is ViewGroup) {
            for (i in 0 until node.childCount) {
                bind(from, to, node.getChildAt(i))
            }
        }
    }
}
