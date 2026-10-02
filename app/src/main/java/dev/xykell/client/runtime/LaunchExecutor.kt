package dev.xykell.client.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.xykell.client.NativeProfiles

/** Executes the decision from LaunchDecider using real device state.
 *  The only success outcome opens Levi's own MainActivity (verified
 *  exported LAUNCHER entry `org.levimc.launcher.ui.activities.MainActivity`
 *  in the v1.5.25 manifest). Never claims the game launched. */
object LaunchExecutor {

    private const val MC_PKG = "com.mojang.minecraftpe"
    private const val LEVI_PKG = "org.levimc.launcher"
    private const val LEVI_MAIN = "org.levimc.launcher.ui.activities.MainActivity"

    data class DeviceState(
        val mcInstalled: Boolean,
        val mcVersion: String?,
        val leviInstalled: Boolean,
        val bridgeUp: Boolean
    )

    fun readState(context: Context): DeviceState {
        val pm = context.packageManager
        var mcVersion: String? = null
        try {
            @Suppress("DEPRECATION")
            mcVersion = pm.getPackageInfo(MC_PKG, 0)?.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            mcVersion = null
        }
        val leviInstalled = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(LEVI_PKG, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        val bridgeUp = try {
            NativeProfiles.getActive(NativeProfiles.root(context))
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        return DeviceState(mcVersion != null, mcVersion, leviInstalled, bridgeUp)
    }

    fun verdictState(context: Context, state: DeviceState): String {
        if (state.mcVersion == null) return "UNKNOWN"
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown-arch"
        return try {
            NativeProfiles.checkVersion(state.mcVersion, abi).substringBefore("|")
        } catch (e: UnsatisfiedLinkError) {
            "UNKNOWN"
        }
    }

    /** Returns the user-facing outcome text. "Opened" means Levi's activity
     *  started — NOT that Minecraft launched. */
    fun execute(context: Context): String {
        val state = readState(context)
        val decision = LaunchDecider.decide(
            state.mcInstalled, state.leviInstalled,
            verdictState(context, state), state.bridgeUp
        )
        if (decision != LaunchDecision.HANDOFF_TO_LEVI) {
            return LaunchDecider.describe(decision)
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClassName(LEVI_PKG, LEVI_MAIN)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            LaunchDecider.describe(decision)
        } catch (e: Exception) {
            "PLAY failed: could not open LeviLauncher (${e.message}). " +
                "Open it manually from your app drawer."
        }
    }
}
