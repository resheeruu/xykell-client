package dev.xykell.client.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.NativeProfiles
import dev.xykell.client.R

/** Real installed-version detection (PackageManager, no guessing) + verdict
 *  from the SHARED native VersionAdapter via JNI. PLAY decisions must use
 *  this verdict — currently nothing installed here can be launched WITH
 *  Xykell config, so PLAY stays NOT WIRED regardless. */
class VersionsFragment : Fragment(R.layout.fragment_info) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.info_title).text = "Versions"
        view.findViewById<TextView>(R.id.info_body).text = describe()
    }

    private fun installedVersion(): String? {
        return try {
            val pm = requireContext().packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo("com.mojang.minecraftpe", 0)
            info.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun describe(): String {
        val v = installedVersion()
        if (v == null) {
            return "Minecraft Bedrock: NOT INSTALLED\n\n" +
                "Install the official Google Play copy first. " +
                "Xykell requires a legitimate installation."
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown-arch"
        val verdict = try {
            NativeProfiles.checkVersion(v, abi)
        } catch (e: UnsatisfiedLinkError) {
            "UNKNOWN|native bridge unavailable: ${e.message}"
        }
        val parts = verdict.split("|", limit = 2)
        val state = parts.getOrElse(0) { "UNKNOWN" }
        val reason = parts.getOrElse(1) { "" }
        return "Minecraft Bedrock: $v ($abi)\n" +
            "Xykell verdict: $state\nReason: $reason\n\n" +
            "Verdict comes from the shared native VersionAdapter " +
            "(Levi floor >=1.21.80; verified lines 1.26.45/1.26.50). " +
            "Launching WITH Xykell config still needs Levi — PLAY NOT WIRED."
    }
}
