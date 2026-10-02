# Launcher integration (bridges + honest gaps)

## Native store bridge (Batch 7: shared code, separate roots)
`app/src/main/cpp/` compiles the SAME ProfileManager/json_min/file_util
sources as the game module (one implementation, no duplication) behind a thin
JNI facade (`NativeProfiles`). The `Profiles` screen is now native-backed:
list/active/export/import all execute real C++ validation; corrupt imports
are rejected natively. Stores remain per-sandbox (Android app vs Levi game
process) — sync travels via export files. JNI runtime itself is
DEVICE_PENDING (compiles in CI; first launch proves `loadLibrary`).

## PLAY: verified handoff (not a direct launch)
Batch 8 source read (Levi v1.5.25 `IntentHandler.java`, Apache-2.0, mechanism
only — nothing copied): Levi handles `minecraft://` URIs itself and forwards
with extras (`MINECRAFT_URI`, `LAUNCH_WITH_URI`); no external action carries
version + isolation + mods, so a bare URI would open the game WITHOUT Xykell
(rejected as fake success).
Implemented instead: `LaunchDecider` (pure) + `LaunchExecutor`
(PackageManager state) — pre-checks (MC installed, shared-adapter verdict
allows, Levi installed, bridge up), then an explicit intent to Levi's
exported `MainActivity`. Success is reported as "Levi opened", never "game
launched"; every failure names its prerequisite.

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
