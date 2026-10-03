package dev.xykell.client.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.NativeSettings
import dev.xykell.client.NativeThemes
import dev.xykell.client.R
import org.json.JSONObject

/**
 * Theme picker (Batch 10): lists the native builtin registry with live
 * swatches, marks the active theme from the validated client.theme
 * setting, previews on tap, and persists through the settings bridge
 * (so invalid names are rejected by native validation, not by UI
 * convention). Bridge missing → honest unavailable state, never a fake
 * list.
 */
class ThemesFragment : Fragment(R.layout.fragment_themes) {

    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private lateinit var activeLabel: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findViewById(R.id.themes_container)
        status = view.findViewById(R.id.themes_status)
        activeLabel = view.findViewById(R.id.themes_active)
        view.findViewById<Button>(R.id.themes_reset).setOnClickListener { reset() }
        render()
    }

    private fun activeName(): String {
        return try {
            val values = JSONObject(NativeSettings.getValues(NativeSettings.root(requireContext())))
            values.optJSONObject("client")?.optString("theme", "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun render() {
        container.removeAllViews()
        val names = NativeThemes.names()
        if (names.isEmpty()) {
            status.text = getString(R.string.themes_unavailable)
            activeLabel.text = ""
            return
        }
        val active = activeName()
        activeLabel.text = getString(R.string.themes_active_label) + ": " +
            (active.ifEmpty { getString(R.string.themes_unknown) })
        status.text = ""
        for (name in names) {
            container.addView(row(name, name == active))
        }
        // Entering the screen: fresh views were inflated with the compiled
        // palette, so re-map them to the active theme.
        ActiveTheme.bindFresh(requireView())
    }

    private fun row(name: String, isActive: Boolean): View {
        val context = requireContext()
        val box = LinearLayout(context)
        box.orientation = LinearLayout.HORIZONTAL
        box.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val density = context.resources.displayMetrics.density
        box.minimumHeight = (48 * density).toInt()
        box.setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())

        val tokens = NativeThemes.tokens(name)?.let { ThemeColors.from(it) }
        for (hex in listOfNotNull(
            tokens?.background,
            tokens?.surface,
            tokens?.accent,
            tokens?.hudAccent,
        )) {
            val swatch = View(context)
            val size = (32 * density).toInt()
            swatch.layoutParams = LinearLayout.LayoutParams(size, size)
            ThemeColors.parseHex(hex)?.let { swatch.setBackgroundColor(it) }
            box.addView(swatch)
        }

        val label = TextView(context)
        label.text = name + if (isActive) "  ● " + getString(R.string.themes_active_badge) else ""
        label.textSize = 16f
        label.setPadding((16 * density).toInt(), 0, 0, 0)
        label.contentDescription = name + if (isActive) ", " + getString(
            R.string.themes_active_badge,
        ) else ""
        box.addView(label)

        box.setOnClickListener {
            if (!isActive) select(name, tokens)
        }
        return box
    }

    private fun select(name: String, tokens: ThemeColors?) {
        if (tokens == null) {
            status.text = getString(R.string.themes_bad_tokens, name)
            return
        }
        val ok = NativeSettings.set(
            NativeSettings.root(requireContext()),
            "client",
            "theme",
            JSONObject.quote(name),
        )
        if (!ok) {
            status.text = getString(R.string.themes_rejected, name)
            return
        }
        ActiveTheme.apply(requireActivity().findViewById(android.R.id.content), tokens)
        render()
    }

    private fun reset() {
        val ok = NativeSettings.reset(NativeSettings.root(requireContext()), "client")
        if (!ok) {
            status.text = getString(R.string.themes_reset_failed)
            return
        }
        ActiveTheme.apply(
            requireActivity().findViewById(android.R.id.content),
            ThemeColors.DEFAULT,
        )
        render()
    }
}
