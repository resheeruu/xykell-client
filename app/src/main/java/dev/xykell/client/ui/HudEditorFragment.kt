package dev.xykell.client.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
    private var overlayStatus: TextView? = null
    private var overlayToggle: Button? = null
    private var overlayGrant: Button? = null

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
        wireOverlay(view)
        view.findViewById<Button>(R.id.hud_reset).setOnClickListener {
            if (NativeHud.reset(root(), profile())) reload()
            else error.text = getString(R.string.hud_layout_reset_failed)
        }
        reload()
    }

    /**
     * The overlay is the only way HUD values reach a real session, so it gets a
     * control here rather than hiding behind a service flag. State is reported
     * from what actually happened (the service is running, the permission is
     * granted) -- never from a preference that could drift from reality.
     */
    private fun wireOverlay(view: View) {
        val status = view.findViewById<TextView>(R.id.hud_overlay_status)
        val toggle = view.findViewById<Button>(R.id.hud_overlay_toggle)
        val grant = view.findViewById<Button>(R.id.hud_overlay_grant)

        overlayStatus = status
        overlayToggle = toggle
        overlayGrant = grant
        refreshOverlay()

        grant.setOnClickListener {
            // The special permission cannot be requested inline: the user has to
            // flip it in system settings, so send them there and re-check on
            // return instead of pretending it was granted.
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}"),
                    ),
                )
            } catch (e: Exception) {
                error.text = getString(R.string.hud_overlay_needs_permission)
            }
        }
        toggle.setOnClickListener {
            val ctx = requireContext()
            if (!HudOverlayService.canDraw(ctx)) {
                error.text = getString(R.string.hud_overlay_needs_permission)
                return@setOnClickListener
            }
            if (HudOverlayService.isRunning()) HudOverlayService.stop(ctx)
            else HudOverlayService.start(ctx)
            refreshOverlay()
        }
    }

    /** Re-reads the real overlay state: granted permission + live service. */
    private fun refreshOverlay() {
        val ctx = context ?: return
        val granted = HudOverlayService.canDraw(ctx)
        val running = granted && HudOverlayService.isRunning()
        overlayStatus?.text = getString(
            if (running) R.string.hud_overlay_running else R.string.hud_overlay_stopped,
        )
        overlayToggle?.text = getString(
            if (running) R.string.hud_overlay_stop else R.string.hud_overlay_start,
        )
        overlayGrant?.visibility = if (granted) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // The permission was granted in system settings, not here, so re-read
        // the real answer rather than trusting the state from onViewCreated.
        refreshOverlay()
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
            empty.text = getString(R.string.hud_empty_layout)
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
                    error.text = getString(R.string.hud_module_toggle_failed, id)
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
        title.text = getString(R.string.hud_element_title, o.optString("type", "element"), index)
        box.addView(title)
        val vis = Switch(context)
        vis.text = getString(R.string.hud_visible)
        vis.isChecked = o.optBoolean("visible", true)
        box.addView(vis)
        val pos = TextView(context)
        var x = o.optDouble("x", 16.0)
        var y = o.optDouble("y", 48.0)
        var scale = o.optDouble("scale", 1.0).coerceIn(0.1, 10.0)
        pos.text = getString(R.string.hud_pos_scale, x.toInt(), y.toInt(), scale)
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
        stepper(getString(R.string.hud_step_x_minus), { x -= it; pos.text = getString(R.string.hud_pos_scale, x.toInt(), y.toInt(), scale) })
        stepper(getString(R.string.hud_step_x_plus), { x += it; pos.text = getString(R.string.hud_pos_scale, x.toInt(), y.toInt(), scale) })
        stepper(getString(R.string.hud_step_y_minus), { y -= it; pos.text = getString(R.string.hud_pos_scale, x.toInt(), y.toInt(), scale) })
        stepper(getString(R.string.hud_step_y_plus), { y += it; pos.text = getString(R.string.hud_pos_scale, x.toInt(), y.toInt(), scale) })
        box.addView(step)
        val bar = SeekBar(context)
        bar.max = 290
        bar.progress = ((scale - 0.1) / 2.9 * 290).roundToInt().coerceIn(0, 290)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                scale = 0.1 + 2.9 * p / 290.0
                pos.text = getString(R.string.hud_pos_scale, x.toInt(), y.toInt(), scale)
            }
            override fun onStartTrackingTouch(b: SeekBar?) = Unit
            override fun onStopTrackingTouch(b: SeekBar?) = Unit
        })
        box.addView(bar)
        val commit = Button(context)
        commit.text = getString(R.string.hud_commit)
        commit.setOnClickListener {
            if (NativeHud.move(root(), profile(), index, x, y, scale, vis.isChecked)) {
                error.text = ""
                reload()
            } else {
                error.text = getString(R.string.hud_commit_rejected, index)
            }
        }
        box.addView(commit)
        return box
    }
}
