# Launcher integration (bridges + honest gaps)

## Native store bridge (Batch 7: shared code, separate roots)
`app/src/main/cpp/` compiles the SAME ProfileManager/json_min/file_util
sources as the game module (one implementation, no duplication) behind a thin
JNI facade (`NativeProfiles`). The `Profiles` screen is now native-backed:
list/active/export/import all execute real C++ validation; corrupt imports
are rejected natively. Stores remain per-sandbox (Android app vs Levi game
process) — sync travels via export files. JNI runtime itself is
DEVICE_PENDING (compiles in CI; first launch proves `loadLibrary`).

## PLAY / Quick Launch verdict
Levi Quick Launch = Minecraft URI actions (screens/servers/Realms/worlds).
Those address the GAME, not a launcher+preloader configuration — no verified
action launches a specific isolated version with Xykell's levipack. PLAY stays
disabled + NOT WIRED. Missing prerequisite: exact Levi launch action for a
configured version (needs device/Levi-source read of the action list).

## Levi manifest census (runtime enablement batch, read-only APK evidence)
Exported entry points found in v1.5.25 manifest strings: `MainActivity`
(LAUNCHER), `SplashActivity`, `IntentHandler`, `InstancesActivity`,
`QuickLaunchActivity`, `MinecraftActivity`, `MinecraftLoadingActivity`,
`ModConfig/ModDetail`, `Accounts/Settings/News/About/Crash/MsftLogin`,
`CurseForge/ExternalMods/ContentManagement` (+ `.levipack`/`.levibackup`
VIEW patterns, FileProvider). No custom `levilauncher://` or `minecraft://`
data scheme and no documented extras for launching a configured
version+mods — direct `VIEW` would open the game WITHOUT Xykell, which is
explicitly rejected as fake success. PLAY verdict stands.

## Safe mode surfacing
Native CrashGuard owns the flag; launcher shows the static contract
(SAFE MODE / Reason / Disabled modules) until a bridge carries live state.
