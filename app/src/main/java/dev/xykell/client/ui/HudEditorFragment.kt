package dev.xykell.client.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.NativeHud
import dev.xykell.client.NativeProfiles
import dev.xykell.client.R
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * HUD configuration experience (Batch 8): element list with visibility,
 * position steppers, scale slider, per-row commit through validated
 * native edits; module toggles for the five local modules; structural
 * preview; profile association (edits land in the named profile);
 * layout reset. Touch-drag with grid snap lives in-game (native
 * applySnapped); here edits are numeric and explicit.
 */
class HudEditorFragment : Fragment(R.layout.fragment_hud_editor) {

    private val localModules = listOf(
        "xykell.hud.fps",
        "xykell.hud.cps",
        "xykell.hud.clock",
        "xykell.hud.session_stats",
        "xykell.hud.stop_watch",
    )

    private lateinit var elementsBox: LinearLayout
    private lateinit var modulesBox: LinearLayout
    private lateinit var preview: TextView
    private lateinit var error: TextView
    private lateinit var profileField: EditText

    private fun root(): String = NativeProfiles.root(requireContext())
    private fun profile(): String = profileField.text.toString().ifBlank { "Default" }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        elementsBox = view.findViewById(R.id.hud_elements)
        modulesBox = view.findViewById(R.id.hud_modules)
        preview = view.findViewById(R.id.hud_preview)
        error = view.findViewById(R.id.hud_error)
        profileField = view.findViewById(R.id.hud_profile)
        try {
            profileField.setText(NativeProfiles.getActive(root()))
        } catch (e: Exception) {
        }
        view.findViewById<Button>(R.id.hud_load).setOnClickListener { reload() }
        view.findViewById<Button>(R.id.hud_reset).setOnClickListener {
            if (NativeHud.reset(root(), profile())) reload()
            else error.text = "Layout reset failed"
        }
        reload()
    }

    private fun layoutJson(): JSONObject {
        return try {
            JSONObject(NativeHud.layout(root(), profile()))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun profileJson(): JSONObject {
        return try {
            JSONObject(NativeProfiles.getProfileJson(root(), profile()) ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun reload() {
        error.text = ""
        val layout = layoutJson()
        val elements = layout.optJSONArray("elements") ?: JSONArray()
        preview.text = HudPreview.renderText(HudPreview.preview(layout.toString()))
        renderModules()
        elementsBox.removeAllViews()
        if (elements.length() == 0) {
            val empty = TextView(requireContext())
            empty.text = "No elements in this profile layout."
            elementsBox.addView(empty)
        }
        for (i in 0 until elements.length()) {
            elementsBox.addView(elementRow(i, elements.optJSONObject(i) ?: JSONObject()))
        }
    }

    private fun renderModules() {
        modulesBox.removeAllViews()
        val mods = try {
            profileJson().optJSONObject("modules")
        } catch (e: Exception) {
            null
        }
        for (id in localModules) {
            val box = LinearLayout(requireContext())
            box.orientation = LinearLayout.HORIZONTAL
            val sw = Switch(requireContext())
            sw.text = id.removePrefix("xykell.hud.")
            sw.isChecked = mods?.optBoolean(id, false) ?: false
            sw.setOnCheckedChangeListener { _, checked ->
                if (!NativeHud.toggleModule(root(), profile(), id, checked)) {
                    sw.isChecked = !checked
                    error.text = "Module toggle failed: $id"
                } else {
                    error.text = ""
                    reload()
                }
            }
            box.addView(sw)
            modulesBox.addView(box)
        }
    }

    private fun elementRow(index: Int, o: JSONObject): View {
        val context = requireContext()
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        val title = TextView(context)
        title.text = "${o.optString("type", "element")} #$index"
        box.addView(title)
        val vis = Switch(context)
        vis.text = "Visible"
        vis.isChecked = o.optBoolean("visible", true)
        box.addView(vis)
        val pos = TextView(context)
        var x = o.optDouble("x", 16.0)
        var y = o.optDouble("y", 48.0)
        var scale = o.optDouble("scale", 1.0).coerceIn(0.1, 10.0)
        pos.text = "x=${x.toInt()} y=${y.toInt()} scale=%.2f".format(scale)
        box.addView(pos)
        val step = LinearLayout(context)
        step.orientation = LinearLayout.HORIZONTAL
        fun stepper(label: String, apply: (Double) -> Unit): Button {
            val b = Button(context)
            b.text = label
            b.setOnClickListener { apply(8.0) }
            step.addView(b)
            return b
        }
        stepper("X-8", { x -= it; pos.text = "x=${x.toInt()} y=${y.toInt()} scale=%.2f".format(scale) })
        stepper("X+8", { x += it; pos.text = "x=${x.toInt()} y=${y.toInt()} scale=%.2f".format(scale) })
        stepper("Y-8", { y -= it; pos.text = "x=${x.toInt()} y=${y.toInt()} scale=%.2f".format(scale) })
        stepper("Y+8", { y += it; pos.text = "x=${x.toInt()} y=${y.toInt()} scale=%.2f".format(scale) })
        box.addView(step)
        val bar = SeekBar(context)
        bar.max = 290
        bar.progress = ((scale - 0.1) / 2.9 * 290).roundToInt().coerceIn(0, 290)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                scale = 0.1 + 2.9 * p / 290.0
                pos.text = "x=${x.toInt()} y=${y.toInt()} scale=%.2f".format(scale)
            }
            override fun onStartTrackingTouch(b: SeekBar?) = Unit
            override fun onStopTrackingTouch(b: SeekBar?) = Unit
        })
        box.addView(bar)
        val commit = Button(context)
        commit.text = "COMMIT ELEMENT"
        commit.setOnClickListener {
            if (NativeHud.move(root(), profile(), index, x, y, scale, vis.isChecked)) {
                error.text = ""
                reload()
            } else {
                error.text = "Element commit rejected (index $index)"
            }
        }
        box.addView(commit)
        return box
    }
}
