package dev.xykell.client.runtime

/** Staged PLAY pipeline. Every stage must pass; the loader stage has no
 *  verified mechanism, so PLAY always stops there with STANDALONE RUNTIME
 *  NOT READY — never a fake "launched". Pure logic (no Android APIs). */
enum class PlayStage {
    MINECRAFT_DETECTED,
    VERSION_COMPATIBLE,
    PROFILE_READY,
    RUNTIME_VALIDATED,
    LOADER,
}

data class StageResult(val stage: PlayStage, val passed: Boolean, val detail: String)

object PlayPipeline {
    fun run(
        mcInstalled: Boolean,
        compatState: String,
        profileReady: Boolean,
        safeMode: Boolean
    ): List<StageResult> {
        val out = ArrayList<StageResult>()
        out.add(if (mcInstalled) {
            StageResult(PlayStage.MINECRAFT_DETECTED, true, "official package present")
        } else {
            return out + StageResult(
                PlayStage.MINECRAFT_DETECTED, false,
                "official Minecraft Bedrock is not installed")
        })
        if (compatState != "SUPPORTED" && compatState != "PARTIAL") {
            return out + StageResult(
                PlayStage.VERSION_COMPATIBLE, false,
                "installed build is not Xykell-compatible ($compatState)")
        }
        out.add(StageResult(
            PlayStage.VERSION_COMPATIBLE, true, "build $compatState"))
        if (!profileReady) {
            return out + StageResult(
                PlayStage.PROFILE_READY, false, "no active profile")
        }
        out.add(StageResult(PlayStage.PROFILE_READY, true, "profile active"))
        if (safeMode) {
            return out + StageResult(
                PlayStage.RUNTIME_VALIDATED, false,
                "safe mode is on — resolve CrashGuard first")
        }
        out.add(StageResult(PlayStage.RUNTIME_VALIDATED, true, "config valid"))
        return out + StageResult(
            PlayStage.LOADER, false,
            "STANDALONE RUNTIME NOT READY: no verified standalone loader " +
            "(signature-derived attach unavailable for this build)")
    }

    fun report(results: List<StageResult>): String {
        val sb = StringBuilder("PLAY pipeline:\n")
        for (r in results) {
            sb.append(if (r.passed) "[OK] " else "[STOP] ")
                .append(r.stage.name).append(": ").append(r.detail).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
