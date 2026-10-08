package dev.xykell.client.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.NativeProfiles
import dev.xykell.client.R
import java.io.File

/** Profiles backed by the SHARED native ProfileManager (same C++ as the game
 *  module) through JNI. Store root is this app's sandbox; the game-process
 *  store is separate — export files bridge them. Corrupt imports are rejected
 *  by native validation, never applied. Batch 11 adds the switcher: tappable
 *  rows, create, and two-tap-armed delete/reset, all surfacing the exact
 *  native rejection reason. */
class ProfilesFragment : Fragment(R.layout.fragment_profiles) {

    private val importCode = 4101

    /** "delete" / "reset" when armed for a second confirming tap, else null. */
    private var armedAction: String? = null

    private fun root(): String = NativeProfiles.root(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refresh(view, null)
        view.findViewById<Button>(R.id.profiles_create).setOnClickListener {
            disarm()
            createProfile(view)
        }
        view.findViewById<Button>(R.id.profiles_delete).setOnClickListener {
            if (armedAction == "delete") {
                disarm()
                runOp(view, 3, activeOr(view) ?: return@setOnClickListener, R.string.profiles_rejected)
            } else {
                arm(view, "delete")
            }
        }
        view.findViewById<Button>(R.id.profiles_reset).setOnClickListener {
            if (armedAction == "reset") {
                disarm()
                runOp(view, 2, activeOr(view) ?: return@setOnClickListener, R.string.profiles_rejected)
            } else {
                arm(view, "reset")
            }
        }
        view.findViewById<Button>(R.id.profiles_export).setOnClickListener {
            disarm()
            exportActive(view)
        }
        view.findViewById<Button>(R.id.profiles_import).setOnClickListener {
            disarm()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, importCode)
        }
    }

    private fun activeOr(view: View): String? {
        val active = try {
            NativeProfiles.getActive(root())
        } catch (e: UnsatisfiedLinkError) {
            view.findViewById<TextView>(R.id.profiles_status).text =
                getString(R.string.profiles_bridge_missing)
            return null
        }
        return active.ifEmpty { null }
    }

    private fun disarm() {
        armedAction = null
        view?.let {
            it.findViewById<Button>(R.id.profiles_delete)?.text = getString(R.string.profiles_delete)
            it.findViewById<Button>(R.id.profiles_reset)?.text = getString(R.string.profiles_reset)
        }
    }

    private fun arm(view: View, action: String) {
        disarm()
        armedAction = action
        val active = activeOr(view) ?: return
        val confirm = if (action == "delete") {
            getString(R.string.profiles_delete_confirm, active)
        } else {
            getString(R.string.profiles_reset_confirm, active)
        }
        view.findViewById<TextView>(R.id.profiles_status).text = confirm
        val button = view.findViewById<Button>(
            if (action == "delete") R.id.profiles_delete else R.id.profiles_reset,
        )
        button.text = confirm
    }

    private fun runOp(view: View, op: Int, name: String, errFmt: Int) {
        val err = NativeProfiles.profileOp(root(), name, op)
        if (err.isEmpty()) {
            val msg = when (op) {
                0 -> getString(R.string.profiles_switched, name)
                1 -> getString(R.string.profiles_created, name)
                2 -> getString(R.string.profiles_reset_done, name)
                else -> getString(R.string.profiles_deleted, name)
            }
            refresh(view, msg)
        } else {
            refresh(view, getString(errFmt, err))
        }
    }

    private fun createProfile(view: View) {
        val field = view.findViewById<EditText>(R.id.profiles_new_name)
        val name = field.text.toString().trim()
        if (name.isEmpty()) {
            view.findViewById<TextView>(R.id.profiles_status).text =
                getString(R.string.profiles_name_empty)
            return
        }
        val err = NativeProfiles.profileOp(root(), name, 1)
        if (err.isEmpty()) {
            field.setText("")
            refresh(view, getString(R.string.profiles_created, name))
        } else {
            refresh(view, getString(R.string.profiles_rejected, err))
        }
    }

    private fun refresh(view: View, status: String?) {
        disarm()
        val rows = view.findViewById<LinearLayout>(R.id.profiles_rows)
        rows.removeAllViews()
        val names: List<String>
        val active: String
        try {
            // Ensure the Default builtin exists on first open (auto-created).
            val r = root()
            NativeProfiles.setActive(r, NativeProfiles.getActive(r))
            names = NativeProfiles.listProfiles(r).toList()
            active = NativeProfiles.getActive(r)
        } catch (e: UnsatisfiedLinkError) {
            view.findViewById<TextView>(R.id.profiles_body).text =
                getString(R.string.profiles_bridge_missing)
            view.findViewById<TextView>(R.id.profiles_status).text = e.message ?: ""
            return
        }
        view.findViewById<TextView>(R.id.profiles_body).text =
            getString(R.string.profiles_store_info)
        for (name in names) {
            rows.addView(profileRow(view, name, name == active))
        }
        if (status != null) {
            view.findViewById<TextView>(R.id.profiles_status).text = status
        }
    }

    private fun profileRow(parent: View, name: String, isActive: Boolean): View {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val box = LinearLayout(context)
        box.orientation = LinearLayout.HORIZONTAL
        box.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        box.minimumHeight = (48 * density).toInt()
        box.setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())

        val label = TextView(context)
        label.text = name + if (isActive) "  ● " + getString(R.string.profiles_active_badge) else ""
        label.textSize = 16f
        label.contentDescription = name + if (isActive) ", " + getString(
            R.string.profiles_active_badge,
        ) else ""
        box.addView(label)

        box.setOnClickListener {
            if (isActive) return@setOnClickListener
            disarm()
            runOp(parent, 0, name, R.string.profiles_rejected)
        }
        return box
    }

    private fun exportActive(view: View) {
        val statusView = view.findViewById<TextView>(R.id.profiles_status)
        try {
            val active = NativeProfiles.getActive(root())
            val json = NativeProfiles.getProfileJson(root(), active)
            if (json == null) {
                statusView.text = getString(R.string.profiles_export_failed_active)
                return
            }
            val dir = File(requireContext().getExternalFilesDir(null), "Xykell")
            if (!dir.exists() && !dir.mkdirs()) {
                statusView.text = getString(R.string.profiles_export_failed_dir)
                return
            }
            val file = File(dir, "profile-$active.json")
            file.writeText(json)
            refresh(view, getString(R.string.profiles_exported_to, file.absolutePath))
        } catch (e: Exception) {
            refresh(view, "Export failed: ${e.message}")
        }
    }

    @Deprecated("Framework picker without new deps; result handled below")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != importCode || resultCode != Activity.RESULT_OK) return
        val uri = data?.data
        val statusTarget: (String) -> Unit = { msg ->
            view?.findViewById<TextView>(R.id.profiles_status)?.text = msg
        }
        if (uri == null) {
            statusTarget("Import cancelled")
            return
        }
        try {
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalArgumentException("empty file")
            val name = "Imported"
            val ok = NativeProfiles.importProfileJson(root(), name, text)
            if (!ok) {
                view?.let { refresh(it, "Import rejected: native validation failed") }
                return
            }
            view?.let { refresh(it, "Imported as '$name' (launcher store; sync to game via files)") }
        } catch (e: Exception) {
            view?.let { refresh(it, "Import failed: ${e.message}") }
        }
    }
}
