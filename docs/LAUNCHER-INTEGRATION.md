# Launcher integration (bridges + honest gaps)

## Native store bridge (Batch 7: shared code, separate roots)
`app/src/main/cpp/` compiles the SAME ProfileManager/json_min/file_util
sources as the game module (one implementation, no duplication) behind a thin
JNI facade (`NativeProfiles`). The `Profiles` screen is now native-backed:
list/active/export/import all execute real C++ validation; corrupt imports
are rejected natively. Stores remain per-sandbox (Android app vs Levi game
process) — sync travels via export files. JNI runtime itself is
DEVICE_PENDING (compiles in CI; first launch proves `loadLibrary`).

## PLAY: staged pipeline, fail-closed loader (standalone migration)
`PlayPipeline` stages: MINECRAFT_DETECTED → VERSION_COMPATIBLE →
PROFILE_READY → RUNTIME_VALIDATED → LOADER. The loader stage has no verified
mechanism, so PLAY always ends `STANDALONE RUNTIME NOT READY` with the exact
blocker — never "Launching...". No Levi references remain in the PLAY path;
the legacy handoff is deleted (migration rule: replacement first — here the
replacement is the honest staged pipeline itself).

## Levi manifest census (runtime enablement batch, read-only APK evidence)
Exported entry points found in v1.5.25 manifest strings: `MainActivity`
(LAUNCHER), `SplashActivity`, `IntentHandler`, `InstancesActivity`,
`QuickLaunchActivity`, `MinecraftActivity`, `MinecraftLoadingActivity`,
`ModConfig/ModDetail`, `Accounts/Settings/News/About/Crash/MsftLogin`,
`CurseForge/ExternalMods/ContentManagement` (+ `.levipack`/`.levibackup`
VIEW patterns, FileProvider). No custom `levilauncher://` or `minecraft://`
data scheme and no documented extras for launching a configured
version+mods — direct `VIEW` would open the game WITHOUT Xykell, which is
explicitly rejected as fake success. (Source read of `IntentHandler.java`
confirmed: URIs route internally with `MINECRAFT_URI`/`LAUNCH_WITH_URI`
extras — no external launch contract.)

## Safe mode surfacing
Native CrashGuard owns the flag; launcher shows the static contract
(SAFE MODE / Reason / Disabled modules) until a bridge carries live state.
