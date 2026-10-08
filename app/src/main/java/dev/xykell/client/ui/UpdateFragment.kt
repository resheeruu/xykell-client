package dev.xykell.client.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.XykellInfo

/**
 * Update center: shows current version/build metadata and
 * update availability state. Honest about missing update
 * infrastructure — no fake updates.
 */
class UpdateFragment : Fragment(R.layout.fragment_update) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.update_title).text = getString(R.string.update_title)
        view.findViewById<TextView>(R.id.update_body).text = getString(R.string.update_body)

        // Current version info
        val versionText = getString(
            R.string.update_current_version,
            XykellInfo.XYKELL_VERSION,
            XykellInfo.NATIVE_VERSION,
            XykellInfo.LEVI_TARGET,
            XykellInfo.PRELOADER_PIN
        )
        view.findViewById<TextView>(R.id.update_current).text = versionText

        // Update status
        val statusView = view.findViewById<TextView>(R.id.update_status)
        statusView.text = getString(R.string.update_status_none)

        // Check for local update metadata (if present)
        checkLocalUpdateMetadata(view)
    }

    private fun checkLocalUpdateMetadata(view: View) {
        // Check for local update metadata file
        val file = java.io.File(requireContext().filesDir, "update_metadata.json")
        if (file.exists()) {
            try {
                val json = file.readText()
                val meta = org.json.JSONObject(json)
                val availableVersion = meta.optString("version", "")
                val changelog = meta.optString("changelog", "")
                val url = meta.optString("url", "")

                if (availableVersion.isNotBlank()) {
                    val statusView = view.findViewById<TextView>(R.id.update_status)
                    statusView.text = getString(R.string.update_status_available, availableVersion)

                    if (changelog.isNotBlank()) {
                        view.findViewById<TextView>(R.id.update_changelog).text = changelog
                        view.findViewById<TextView>(R.id.update_changelog).visibility = View.VISIBLE
                    }
                    if (url.isNotBlank()) {
                        val button =
                            view.findViewById<android.widget.Button>(R.id.update_download)
                        button.visibility = View.VISIBLE
                        button.setOnClickListener {
                            // Open the update page in the browser; never
                            // download or execute anything from here.
                            val intent =
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                )
                            try {
                                startActivity(intent)
                            } catch (e: Exception) {
                                view.findViewById<TextView>(R.id.update_status).text =
                                    getString(R.string.update_open_failed)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore malformed metadata
            }
        }
    }
}