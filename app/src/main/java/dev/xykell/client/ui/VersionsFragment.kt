package dev.xykell.client.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.NativeProfiles
import dev.xykell.client.R

/** Real installed-version detection (PackageManager, no guessing) + verdicts
 *  from the SHARED native logic via JNI. States never collapse a visibility
 *  failure into "not installed" — see native detection.h. */
class VersionsFragment : Fragment(R.layout.fragment_info) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.info_title).text = "Versions"
        view.findViewById<TextView>(R.id.info_body).text = describe()
    }

    private fun describe(): String {
        val pm = requireContext().packageManager
        val pkg = "com.mojang.minecraftpe"
        val info = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (info == null) {
            return "Minecraft\nNot detected\n\n" +
                "Package: $pkg\n" +
                "Detection: PackageManager lookup failed\n" +
                "Note: this build declares <queries> for $pkg, so a miss " +
                "means genuinely absent (or a work-profile/private-space " +
                "install invisible to this app)."
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown-arch"
        @Suppress("DEPRECATION")
        val appInfo = info.applicationInfo
        val enabled = appInfo?.enabled == true
        @Suppress("DEPRECATION")
        val installer = try {
            pm.getInstallerPackageName(pkg) ?: "(unknown)"
        } catch (e: Exception) {
            "(unavailable)"
        }
        val splits = appInfo?.splitNames?.size ?: 0
        val version = info.versionName ?: ""
        @Suppress("DEPRECATION")
        val versionCode = info.versionCode
        val installVerdict = try {
            NativeProfiles.checkInstall(true, version, abi, enabled, true)
        } catch (e: UnsatisfiedLinkError) {
            "UNKNOWN|native bridge unavailable"
        }
        val compatVerdict = try {
            NativeProfiles.checkVersion(version, abi)
        } catch (e: UnsatisfiedLinkError) {
            "UNKNOWN|native bridge unavailable"
        }
        return "Minecraft\nInstalled — $version ($versionCode)\n\n" +
            "Package: $pkg\n" +
            "Detection: PackageManager (install verdict: $installVerdict)\n" +
            "Compatibility: $compatVerdict\n" +
            "Enabled: $enabled\n" +
            "Installer: $installer\n" +
            "ABI: $abi\n" +
            "Splits: $splits\n" +
            "Launching WITH Xykell config needs the standalone loader — " +
            "PLAY reports its exact stage."
    }
}
