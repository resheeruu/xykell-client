package dev.xykell.client.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.xykell.client.NativeProfiles

/** Executes the staged PLAY pipeline using real device state. Contains NO
 *  Levi references: the final architecture never opens another launcher.
 *  Success beyond the loader stage is impossible today, so this always ends
 *  with the exact blocker — never "Launching..." or "Started". */
object LaunchExecutor {

    private const val MC_PKG = "com.mojang.minecraftpe"

    fun execute(context: Context): String {
        val pm = context.packageManager
        val mcVersion: String? = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(MC_PKG, 0)?.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown-arch"
        val compatState = if (mcVersion == null) {
            "UNKNOWN"
        } else try {
            NativeProfiles.checkVersion(mcVersion, abi).substringBefore("|")
        } catch (e: UnsatisfiedLinkError) {
            "UNKNOWN"
        }
        var profileReady = false
        var safeMode = false
        try {
            val root = NativeProfiles.root(context)
            profileReady = NativeProfiles.getActive(root).isNotEmpty()
        } catch (e: UnsatisfiedLinkError) {
            return "PLAY pipeline:\n[STOP] NATIVE_BRIDGE: unavailable (${e.message})"
        }
        // Safe mode lives in the game-process store; the launcher cannot read
        // it yet — assume clear, say so (never claim otherwise).
        val results = PlayPipeline.run(
            mcInstalled = mcVersion != null,
            compatState = compatState,
            profileReady = profileReady,
            safeMode = safeMode
        )
        return PlayPipeline.report(results)
    }

    /**
     * Xykell-owned Minecraft launch (Stage 7). Legitimate system intent only:
     * validate installation, start the Xykell runtime if available, fire
     * Minecraft's own exported launcher activity, record an owned session.
     * Launching is NOT a runtime connection: the report always states that
     * the Xykell runtime is not connected to Minecraft.
     */
    fun launchMinecraft(context: Context): String {
        val pm = context.packageManager
        val versionName: String? = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(MC_PKG, 0)?.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (versionName == null) {
            return "Minecraft Installed: NO ($MC_PKG not found)\n" +
                "Minecraft Launchable: NO\nXykell Runtime: not started"
        }
        val runtimeOk = RuntimeStatus.start()
        val sessionLine = RuntimeStatus.beginSession(MC_PKG, versionName).ifEmpty {
            "session unavailable (native bridge missing)"
        }
        val intent = pm.getLaunchIntentForPackage(MC_PKG)
        if (intent == null) {
            RuntimeStatus.endSession("no exported launch intent")
            return "Minecraft Installed: YES ($versionName)\n" +
                "Minecraft Launchable: NO (no exported launch intent)\n" +
                "Xykell Runtime Available: $runtimeOk ($sessionLine)"
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            RuntimeStatus.markLaunched()
            "Minecraft Installed: YES ($versionName)\n" +
                "Minecraft Launchable: YES (system intent)\n" +
                "Xykell Runtime Available: $runtimeOk ($sessionLine)\n" +
                "Minecraft Runtime Connected: NO " +
                "(launch is not connection; no attachment mechanism)"
        } catch (e: Exception) {
            RuntimeStatus.endSession("launch failed: ${e.message}")
            "Minecraft Installed: YES ($versionName)\n" +
                "Minecraft Launchable: YES\n" +
                "Launch: FAILED (${e.message})"
        }
    }
}
