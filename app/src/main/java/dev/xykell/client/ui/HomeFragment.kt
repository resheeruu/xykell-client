package dev.xykell.client.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.LaunchExecutor
import dev.xykell.client.runtime.ProfileManager
import dev.xykell.client.runtime.RuntimeManager
import dev.xykell.client.runtime.XykellInfo

class HomeFragment : Fragment(R.layout.fragment_home) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.home_title).text = "XYKELL CLIENT"
        view.findViewById<TextView>(R.id.home_versions).text =
            "Minecraft: " + installedLine()
        view.findViewById<TextView>(R.id.home_xykell).text =
            "Xykell ${XykellInfo.XYKELL_VERSION} (native ${XykellInfo.NATIVE_VERSION})"
        view.findViewById<TextView>(R.id.home_profile).text =
            "Profile: ${ProfileManager.currentProfile}"
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
    }

    private fun installedLine(): String {
        return try {
            @Suppress("DEPRECATION")
            val info = requireContext().packageManager
                .getPackageInfo("com.mojang.minecraftpe", 0)
            "${info.versionName} (installed)"
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            "not installed"
        }
    }
}
