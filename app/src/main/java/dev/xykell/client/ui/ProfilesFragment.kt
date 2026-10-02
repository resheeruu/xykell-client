package dev.xykell.client.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.NativeProfiles
import dev.xykell.client.R
import java.io.File

/** Profiles backed by the SHARED native ProfileManager (same C++ as the game
 *  module) through JNI. Store root is this app's sandbox; the game-process
 *  store is separate — export files bridge them. Corrupt imports are rejected
 *  by native validation, never applied. */
class ProfilesFragment : Fragment(R.layout.fragment_profiles) {

    private val importCode = 4101

    private fun root(): String = NativeProfiles.root(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refresh(view, null)
        view.findViewById<Button>(R.id.profiles_export).setOnClickListener {
            exportActive(view)
        }
        view.findViewById<Button>(R.id.profiles_import).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, importCode)
        }
    }

    private fun refresh(view: View, status: String?) {
        val names: List<String>
        val active: String
        try {
            // Ensure the Default builtin exists on first open (auto-created).
            val root = root()
            NativeProfiles.setActive(root, NativeProfiles.getActive(root))
            names = NativeProfiles.listProfiles(root).toList()
            active = NativeProfiles.getActive(root)
        } catch (e: UnsatisfiedLinkError) {
            view.findViewById<TextView>(R.id.profiles_body).text =
                "Native bridge unavailable: ${e.message}"
            return
        }
        view.findViewById<TextView>(R.id.profiles_body).text =
            "Active: $active\nAvailable: ${if (names.isEmpty()) "(none yet)" else names.joinToString()}\n\n" +
            "Native-backed (shared ProfileManager). Game-process store is " +
            "separate — sync via export files."
        if (status != null) {
            view.findViewById<TextView>(R.id.profiles_status).text = status
        }
    }

    private fun exportActive(view: View) {
        val statusView = view.findViewById<TextView>(R.id.profiles_status)
        try {
            val active = NativeProfiles.getActive(root())
            val json = NativeProfiles.getProfileJson(root(), active)
            if (json == null) {
                statusView.text = "Export failed: no readable active profile"
                return
            }
            val dir = File(requireContext().getExternalFilesDir(null), "Xykell")
            if (!dir.exists() && !dir.mkdirs()) {
                statusView.text = "Export failed: cannot create Xykell dir"
                return
            }
            val file = File(dir, "profile-$active.json")
            file.writeText(json)
            refresh(view, "Exported to ${file.absolutePath}")
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
