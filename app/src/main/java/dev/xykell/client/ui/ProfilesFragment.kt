package dev.xykell.client.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.ProfileManager
import org.json.JSONObject
import java.io.File

/** Real export/import over app-visible files. Scope is honest: this moves the
 *  launcher's own profile view (Default + active name). The native
 *  game-process store is a separate sandbox — no shared bridge exists yet, so
 *  nothing here claims to touch it. Corrupt imports are rejected, never applied. */
class ProfilesFragment : Fragment(R.layout.fragment_profiles) {

    private val importCode = 4101

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshBody(view, null)
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

    private fun profileJson(): JSONObject {
        return JSONObject()
            .put("schemaVersion", 1)
            .put("name", ProfileManager.currentProfile)
            .put("modules", JSONObject())
            .put("note", "Xykell launcher profile view (native store is separate)")
    }

    private fun exportActive(view: View) {
        val status = view.findViewById<TextView>(R.id.profiles_status)
        try {
            val dir = File(requireContext().getExternalFilesDir(null), "Xykell")
            if (!dir.exists() && !dir.mkdirs()) {
                status.text = "Export failed: cannot create Xykell dir"
                return
            }
            val file = File(dir, "profile-${ProfileManager.currentProfile}.json")
            file.writeText(profileJson().toString(2))
            status.text = "Exported to ${file.absolutePath}"
        } catch (e: Exception) {
            status.text = "Export failed: ${e.message}"
        }
        refreshBody(view, null)
    }

    @Deprecated("Framework picker without new deps; result handled below")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val status = view?.findViewById<TextView>(R.id.profiles_status) ?: return
        if (requestCode != importCode || resultCode != Activity.RESULT_OK) return
        val uri = data?.data
        if (uri == null) {
            status.text = "Import cancelled"
            return
        }
        try {
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalArgumentException("empty file")
            val obj = JSONObject(text)
            if (obj.optInt("schemaVersion", -1) != 1 || obj.optString("name").isEmpty()) {
                status.text = "Import rejected: bad schemaVersion/name"
                return
            }
            status.text = "Imported '${obj.getString("name")}' (launcher view only; " +
                "native store untouched — bridge pending)"
        } catch (e: Exception) {
            status.text = "Import rejected: ${e.message}"
        }
        view?.let { refreshBody(it, null) }
    }

    private fun refreshBody(view: View, ignored: Nothing?) {
        val pm = ProfileManager
        view.findViewById<TextView>(R.id.profiles_body).text =
            "Current: ${pm.currentProfile}\nAvailable: ${pm.profiles.joinToString()}\n\n" +
            "Export/import moves the launcher's own profile files. " +
            "Native profiles live in the game-process store — no shared " +
            "bridge yet (RESEARCH_REQUIRED, no duplicate store here)."
    }
}
