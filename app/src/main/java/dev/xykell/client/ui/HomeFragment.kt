package dev.xykell.client.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dev.xykell.client.NativeProfiles
import dev.xykell.client.R
import dev.xykell.client.runtime.LaunchExecutor
import dev.xykell.client.runtime.ProfileManager
import dev.xykell.client.runtime.RuntimeManager
import dev.xykell.client.runtime.XykellInfo
import dev.xykell.client.runtime.observation.ObservationService

class HomeFragment : Fragment(R.layout.fragment_home) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.home_title).text = "XYKELL CLIENT"
        bindStatus(view)
        val play = view.findViewById<Button>(R.id.home_play)
        val status = view.findViewById<TextView>(R.id.home_play_status)
        // PLAY = Xykell-owned path: staged checks, then Minecraft's own
        // exported activity via system intent. Never claims the game
        // launched or any runtime connection. See LaunchExecutor.
        play.isEnabled = true
        status.text = RuntimeManager.playStatusText()
        play.setOnClickListener {
            status.text = LaunchExecutor.execute(requireContext()) + "\n\n" +
                LaunchExecutor.launchMinecraft(requireContext())
        }
        val diag = view.findViewById<TextView>(R.id.home_diag)
        diag.text = "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "Android ${Build.VERSION.RELEASE}, " +
            (Build.SUPPORTED_ABIS.firstOrNull() ?: "abi?") +
            "\n" + dev.xykell.client.runtime.RuntimeStatus.summary()
        // Stage 20 (Phase 1): explicit observation Start/Stop + state text.
        // No settings, no endpoint configuration, no diagnostics.
        val obsStatus = view.findViewById<TextView>(R.id.home_obs_status)
        val refreshObs = {
            obsStatus.text = ObservationService.statusText()
            bindStatus(view)
        }
        refreshObs()
        view.findViewById<Button>(R.id.home_obs_start).setOnClickListener {
            ObservationService.start(requireContext())
            refreshObs()
        }
        view.findViewById<Button>(R.id.home_obs_stop).setOnClickListener {
            ObservationService.stop(requireContext())
            refreshObs()
        }
    }

    // Row order must match layout value ids: Minecraft, Profile, Xykell,
    // Runtime, Observation.
    private val rowValueIds = intArrayOf(
        R.id.home_mc, R.id.home_profile, R.id.home_version,
        R.id.home_runtime, R.id.home_obs
    )

    private fun bindStatus(view: View) {
        val rows = HomeStatus.rows(
            minecraftVersion = installedVersion(),
            profile = activeProfile(),
            xykellVersion = XykellInfo.XYKELL_VERSION,
            nativeVersion = XykellInfo.NATIVE_VERSION,
            runtimeSummary = dev.xykell.client.runtime.RuntimeStatus.summary(),
            observationState = ObservationService.state.name
        )
        for ((index, row) in rows.withIndex()) {
            val value = view.findViewById<TextView>(rowValueIds[index])
            value.text = row.value
            value.setTextColor(toneColor(row.tone))
        }
    }

    private fun toneColor(tone: StatusTone): Int = ContextCompat.getColor(
        requireContext(),
        when (tone) {
            StatusTone.OK -> R.color.xykell_success
            StatusTone.WARN -> R.color.xykell_warning
            StatusTone.ERROR -> R.color.xykell_error
            StatusTone.MUTED -> R.color.xykell_muted
        }
    )

    // Native active profile (shared store with the game module);
    // bridge missing → launcher-side fallback, never a fake value.
    private fun activeProfile(): String {
        val native = try {
            NativeProfiles.getActive(NativeProfiles.root(requireContext()))
        } catch (e: UnsatisfiedLinkError) {
            ""
        }
        return native.ifEmpty { ProfileManager.currentProfile }
    }

    private fun installedVersion(): String? {
        return try {
            @Suppress("DEPRECATION")
            val info = requireContext().packageManager
                .getPackageInfo("com.mojang.minecraftpe", 0)
            info.versionName ?: "?"
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            null
        }
    }
}
