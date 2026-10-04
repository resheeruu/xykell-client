package dev.xykell.client.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.LaunchExecutor
import dev.xykell.client.runtime.ProfileManager
import dev.xykell.client.runtime.XykellInfo
import dev.xykell.client.runtime.observation.ObservationService

/**
 * Pre-launch session configuration: shows selected profile, theme,
 * HUD, content, world/server target, and launch readiness.
 */
class SessionFragment : Fragment(R.layout.fragment_session) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.session_title).text = getString(R.string.session_title)
        view.findViewById<TextView>(R.id.session_body).text = getString(R.string.session_body)

        refresh(view)

        view.findViewById<Button>(R.id.session_launch).setOnClickListener {
            val status = LaunchExecutor.execute(requireContext()) + "\n\n" +
                LaunchExecutor.launchMinecraft(requireContext())
            view.findViewById<TextView>(R.id.session_status).text = status
        }
    }

    private fun refresh(view: View) {
        // Profile
        val profile = ProfileManager.currentProfile
        view.findViewById<TextView>(R.id.session_profile).text = getString(R.string.session_profile_label, profile)

        // Theme
        val theme = try {
            val values = org.json.JSONObject(dev.xykell.client.NativeSettings.getValues(dev.xykell.client.NativeSettings.root(requireContext())))
            values.optJSONObject("client")?.optString("theme", "") ?: dev.xykell.client.ui.ThemeColors.DEFAULT.name
        } catch (e: Exception) {
            dev.xykell.client.ui.ThemeColors.DEFAULT.name
        }
        view.findViewById<TextView>(R.id.session_theme).text = getString(R.string.session_theme_label, theme)

        // Xykell version
        view.findViewById<TextView>(R.id.session_version).text = getString(
            R.string.session_version_label,
            XykellInfo.XYKELL_VERSION,
            XykellInfo.NATIVE_VERSION
        )

        // Observation state
        val obsState = ObservationService.state.name
        view.findViewById<TextView>(R.id.session_observation).text = getString(R.string.session_observation_label, obsState)

        // Runtime status
        val runtime = dev.xykell.client.runtime.RuntimeStatus.summary()
        val firstLine = runtime.lineSequence().firstOrNull().orEmpty().removePrefix("Runtime:").trim()
        val runtimeDisplay = firstLine.ifEmpty { getString(R.string.session_runtime_unknown) }
        view.findViewById<TextView>(R.id.session_runtime).text = getString(R.string.session_runtime_label, runtimeDisplay)

        // Launch status
        view.findViewById<TextView>(R.id.session_status).text = LaunchExecutor.execute(requireContext()) + "\n\n" +
            LaunchExecutor.launchMinecraft(requireContext())
    }
}