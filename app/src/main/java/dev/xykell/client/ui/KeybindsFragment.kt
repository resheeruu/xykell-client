package dev.xykell.client.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.NativeSettings
import dev.xykell.client.NativeKeybinds
import dev.xykell.client.R

/**
 * One-shot key capture sink bridged from MainActivity.dispatchKeyEvent
 * while the keybind editor is capturing. The launcher records only the
 * abstract host code (Android keycode) — never keystroke content — and
 * the sink is cleared on capture end or when the editor view dies.
 */
object KeybindCapture {
    @Volatile
    var sink: ((Int) -> Boolean)? = null
}

/**
 * Keybind editor (Batch 13): lists the native default actions with
 * primary/secondary binds, one-shot capture of the next host keycode
 * (volume keys work on-device; other codes need an external keyboard),
 * per-row clear, reset-all. Every mutation goes through the bounded
 * native bridge and surfaces its exact error; the bridge being missing
 * is an honest unavailable state, never a fake list.
 */
class KeybindsFragment : Fragment(R.layout.fragment_keybinds) {

    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var cancel: Button
    private var root: String = ""
    private var captureAction: String? = null
    private var captureSlot: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rows = view.findViewById(R.id.keybind_rows)
        status = view.findViewById(R.id.keybind_status)
        cancel = view.findViewById(R.id.keybind_cancel)
        root = NativeSettings.root(requireContext())
        view.findViewById<Button>(R.id.keybind_reset).setOnClickListener { resetAll() }
        cancel.setOnClickListener {
            endCapture(getString(R.string.keybinds_capture_cancelled))
        }
        render()
    }

    override fun onDestroyView() {
        KeybindCapture.sink = null
        captureAction = null
        super.onDestroyView()
    }

    private fun render(statusText: String? = null) {
        rows.removeAllViews()
        val bindings = NativeKeybinds.list(root)
        if (bindings == null) {
            status.text = getString(R.string.keybinds_unavailable)
            cancel.visibility = View.GONE
            return
        }
        status.text = statusText ?: bindings.loadError
        for (bind in bindings.binds) {
            rows.addView(rowFor(bind))
        }
        ActiveTheme.bindFresh(requireView())
    }

    private fun rowFor(bind: NativeKeybinds.Bind): View {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val box = LinearLayout(context)
        box.orientation = LinearLayout.HORIZONTAL
        box.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        box.minimumHeight = (48 * density).toInt()
        box.setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())

        val name = TextView(context)
        name.text = bind.action
        name.textSize = 14f
        name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        box.addView(name)

        val primary = slotButton(bind.action, 0, bind.primary)
        box.addView(primary)

        val secondary = slotButton(bind.action, 1, bind.secondary)
        box.addView(secondary)

        val clear = Button(context)
        clear.text = getString(R.string.keybinds_clear)
        clear.textSize = 12f
        clear.minWidth = 0
        clear.minHeight = (40 * density).toInt()
        clear.setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
        clear.contentDescription = getString(R.string.keybinds_clear_desc, bind.action)
        clear.setOnClickListener { clearBoth(bind.action) }
        box.addView(clear)

        return box
    }

    private fun slotButton(action: String, slot: Int, code: Int): Button {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val button = Button(context)
        button.text = KeyLabels.label(code)
        button.textSize = 12f
        button.minWidth = 0
        button.minHeight = (40 * density).toInt()
        button.setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        val slotName = getString(if (slot == 0) R.string.keybinds_primary else R.string.keybinds_secondary)
        button.contentDescription =
            getString(R.string.keybinds_slot_desc, action, slotName, KeyLabels.label(code))
        button.setOnClickListener { startCapture(action, slot) }
        return button
    }

    private fun startCapture(action: String, slot: Int) {
        captureAction = action
        captureSlot = slot
        cancel.visibility = View.VISIBLE
        val prompt = getString(
            if (slot == 0) R.string.keybinds_press_primary else R.string.keybinds_press_secondary,
            action,
        )
        status.text = prompt
        KeybindCapture.sink = { keyCode ->
            val act = captureAction
            if (act == null) {
                false
            } else {
                applyBind(act, captureSlot, keyCode)
                true
            }
        }
    }

    private fun endCapture(message: String) {
        KeybindCapture.sink = null
        captureAction = null
        cancel.visibility = View.GONE
        status.text = message
    }

    private fun applyBind(action: String, slot: Int, code: Int) {
        val error = NativeKeybinds.set(root, action, slot, code)
        KeybindCapture.sink = null
        captureAction = null
        cancel.visibility = View.GONE
        val message = if (error.isEmpty()) {
            getString(R.string.keybinds_bound, action, KeyLabels.label(code))
        } else {
            error  // exact native error, never reworded
        }
        if (isAdded && view != null) {
            render(message)
        }
    }

    private fun clearBoth(action: String) {
        val first = NativeKeybinds.set(root, action, 0, 0)
        val message = if (first.isNotEmpty()) {
            first
        } else {
            val second = NativeKeybinds.set(root, action, 1, 0)
            if (second.isNotEmpty()) second else getString(R.string.keybinds_cleared, action)
        }
        render(message)
    }

    private fun resetAll() {
        val error = NativeKeybinds.reset(root)
        val message = if (error.isEmpty()) {
            getString(R.string.keybinds_reset_done)
        } else {
            error
        }
        render(message)
    }
}
