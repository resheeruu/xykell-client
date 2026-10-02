# Android native spec (Approach A)

## 1. Baseline
Android 9 (API 28)+, ARM64 (arm64-v8a). Legitimate Google Play Bedrock copy required — launcher verifies presence, never ships game binaries. Build: Android Studio + JDK 21+, SDK API 28+, NDK + CMake (versions pinned in `launcher/android/` when created; verify on this machine before native coding).

## 2. Packaging
Native mods ship as `preload-native` packs: `manifest.json` (`type`, `name`, `author`, `version`, `entry` .so path, `minecraft_versions`, icon) + `lib*.so` + `config/config.json` + `config/config.schema.json`. Directory name = stable runtime mod id (paths, Mod Menu ownership, persisted state).

## 3. Lifecycle (one long-lived object per module)
Register → load config (typed, schema-checked) → declare version availability → subscribe to event bus → enable/disable/unload. Hook/patch handles are mod-owned state, released on unload. Core keeps `NativeMod` identity for ownership and diagnostics.

## 4. Permissions
Storage (versions/worlds/packs/backups), network (explicit update check only), input/display overlay for HUD/ClickGUI, nothing else without a documented reason and user grant. No accessibility-abuse, no device-admin, no credential stores.

## 5. Signatures/offsets policy
Derived through our own pipeline per Bedrock release, stored as data in `client/compatibility/`, with provenance recorded. Never paste offsets from other clients. Stale data fails closed (module unloads, message shown).

## 6. Failure modes → behavior
| Failure | Behavior |
|---|---|
| Play copy missing/unsupported source | Launcher blocks launch, explains fix |
| Imported version won't start | Advise version isolation, show launch log reason |
| Unsupported Bedrock build | No attach; "unavailable on this version" |
| Hook target not found (stale sigs) | Module unloads; core degraded; diagnostic names the symbol |
| Native crash | Redacted tombstone; quarantine persisted; next launch offers safe mode |
| Config corrupt | Back up bad file, regenerate defaults, log |
| Storage full / perm denied | Degraded path, explicit message, no silent loss |
| MC restart | Core re-attaches idempotently; overlay/input state restored from config |

## 7. Recovery strategy
Safe mode (core only) is always reachable from launcher. Quarantine list + last-known-good config + local backups make every failure reversible. Crash logs redact tokens/paths/IDs.
