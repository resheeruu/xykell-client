# Xykell — Architecture (approved spec, Approach A)

Status: APPROVED 2026-10-02. Native Android ARM64 + C++ preloader core, per-version compat layer. MITM (B) and packs/scripting (C) stay isolated optional fallbacks. No auth/DRM/anti-cheat/platform-security bypasses — ever.

## 1. Process model
- `launcher/android` (Kotlin/Java, Gradle): owns the Play-copy license check, version isolation, launch flow, native-module loading, touch UI shell, Mod Menu host. Never touches game memory directly.
- `client/` (C++ `.so`, arm64-v8a, `preload-native` layout): loaded into the game process by the launcher's preloader. Owns: module registry, lifecycle, event bus, JSON config + profiles, keybind/touchbind, theme, VersionAdapter, redacted logging, crash handling, diagnostics, signed update check, render/input/network abstractions.
- `modules/<category>/`: each module = metadata + category + settings + lifecycle + compatibility + implementation. Independently toggleable. A faulting module is quarantined (per-module exception boundary + watchdog) and must never crash the client.
- Fallbacks B/C live behind feature flags, off by default, in their own directories. No shared mutable state with the native core.

## 2. Loading mechanism (detail in docs/ANDROID-NATIVE.md)
Launcher imports the official Play APK → starts the game in an isolated profile → preloader maps `libxykell-core.so` + enabled module `.so`s into the process → `PL_REGISTER_MOD`-style single lifecycle entry per module → core verifies Bedrock build, loads compat data, mounts event bus, draws minimal overlay. No system install, no root, no game-binary redistribution.

## 3. Event bus
Single typed bus: game lifecycle (start/stop/world-load), input (touch/key/controller), render (frame pre/post, overlay layer), network (connection state — never credential material), config/profile change. Subscribers declare version availability; bus drops events for disabled/unsupported modules silently with a counter.

## 4. Configuration & profiles
JSON under launcher-managed storage. Schema-versioned (`config.schema.json` per module). Profiles: Default/PvP/Survival/Performance/Recording/Low-End/Touch/Advanced. Manual + per-world/per-server selection, import/export, backup/restore/reset. Corrupt config → back up the bad file, regenerate defaults, log — never crash.

## 5. Render/input/network abstractions
Render: overlay layer only (HUD/ClickGUI), no game-renderer replacement in M1. Input: touch-first binder (tap/swipe/hold/gesture + floating buttons), key/controller passthrough. Network: status/diagnostics only in M1; any packet work is a future isolated experiment, audited, off by default.

## 6. Failure modes & recovery
- Unsupported Bedrock build → refuse native attach, show "Xykell module unavailable on this Minecraft version.", launcher still manages versions/content.
- Missing/stale signatures → affected modules stay unloaded, core runs degraded, diagnostics pinpoints the exact symbol.
- Native crash → tombstone + redacted crash log, module quarantine list persisted, next launch offers safe mode (core only, all modules off).
- Config corruption, storage-full, permission-denied: each has a defined degraded path (see ANDROID-NATIVE.md). No silent data loss.

## 7. Modes & safety gates
SAFE default (perf/HUD/visual/QoL). QOL → ADVANCED → EXPERIMENTAL each require explicit user opt-in with a warning screen. Advanced/Experimental modules ship disabled and can never self-enable.

## 8. Security (non-negotiable, see docs/LEGAL-LICENSE.md)
No credential/token/MS-password handling, no hidden telemetry/requests, no RCE, no arbitrary downloaded code, signed releases + checksum verification, local-only backups, redacted logs.

## 9. What is explicitly NOT built
Auth/DRM bypasses, server-compromise or DoS tooling, packet attacks, account/protection bypasses, copied proprietary code/assets/offsets/branding, fake placeholder modules.
