package dev.xykell.client.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.ProfileManager
import dev.xykell.client.runtime.RuntimeManager
import dev.xykell.client.runtime.XykellInfo

class HomeFragment : Fragment(R.layout.fragment_home) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.home_title).text = "XYKELL CLIENT"
        view.findViewById<TextView>(R.id.home_versions).text =
            "Minecraft: NOT WIRED (version scan pending)"
        view.findViewById<TextView>(R.id.home_xykell).text =
            "Xykell ${XykellInfo.XYKELL_VERSION} (native ${XykellInfo.NATIVE_VERSION})"
        view.findViewById<TextView>(R.id.home_profile).text =
            "Profile: ${ProfileManager.currentProfile}"
        val play = view.findViewById<Button>(R.id.home_play)
        val status = view.findViewById<TextView>(R.id.home_play_status)
        // Backend unavailable: visibly disabled + persistent reason. No fake launch.
        play.isEnabled = false
        status.text = RuntimeManager.playStatusText()
        val diag = view.findViewById<TextView>(R.id.home_diag)
        diag.text = "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "Android ${Build.VERSION.RELEASE}, " +
            (Build.SUPPORTED_ABIS.firstOrNull() ?: "abi?")
    }
}
