# Xykell — Architecture (approved spec, Approach A)

Status: APPROVED 2026-10-02. Native Android ARM64 + C++ preloader core on LeviLaunchroid, per-version compat layer. MITM (B) and packs/scripting (C) stay isolated optional fallbacks. No auth/DRM/anti-cheat/platform-security bypasses — ever.

## 1. Layer map (§7)
```
Xykell Android/UI Layer
Xykell Native Core
Xykell Module Engine
Xykell Event Engine + Render Engine + Input Engine
Version Adapter + Configuration + Profile Manager + Compatibility Manager
Network/Packet Layer + Script Engine
      ↓
LeviLaunchroid Preloader
      ↓
Minecraft Bedrock Android
```
Details per layer: LEVI-INTEGRATION.md (foundation), MODULE-SYSTEM.md (engine+API), UI-SYSTEM.md, NETWORK-ARCHITECTURE.md, SCRIPTING.md, PERFORMANCE.md, BEDROCK-COMPATIBILITY.md.

## 2. Process model
- `launcher/android` (Kotlin/Java, Gradle): owns the Play-copy license check, version isolation, launch flow, native-module loading, touch UI shell, Mod Menu host. Never touches game memory directly.
- `client/` (C++ `.so`, arm64-v8a, `preload-native` layout): loaded into the game process by the launcher's preloader. Owns: module registry, lifecycle, event bus, JSON config + profiles, keybind/touchbind, theme, VersionAdapter, redacted logging, crash handling, diagnostics, signed update check, render/input/network abstractions.
- `modules/<category>/`: each module = manifest (id, category, capabilities, deps, settings, default state, binds, compat) + implementation. Independently toggleable. A faulting module is quarantined (per-module exception boundary + watchdog) and must never crash the client.
- Fallbacks B/C live behind feature flags, off by default, in their own directories. No shared mutable state with the native core.

## 3. Event bus
Single typed bus: game lifecycle, input, render (frame pre/post, overlay), network state (never credential material), config/profile change. Subscribers declare version availability; bus drops events for disabled/unsupported modules with a counter.

## 4. Configuration & profiles
JSON under launcher-managed storage. Schema-versioned. Profiles: DEFAULT/QOL/PVP/SURVIVAL/PERFORMANCE/LOW_END/RECORDING/TOUCH/ADVANCED/EXPERIMENTAL/CUSTOM. Manual + per-world/per-server selection, import/export (`XykellProfile.json`), backup/restore/reset. Corrupt config → back up the bad file, regenerate defaults, log — never crash.

## 5. Render/input/network abstractions
Render: overlay layer only (HUD/ClickGUI), no game-renderer replacement in M1. Input: touch-first binder (tap/swipe/hold/gesture + floating buttons), key/controller passthrough. Network: status/diagnostics only in M1; any packet work is a future isolated experiment, audited, off by default.

## 6. Module states & failure modes
States: SUPPORTED / PARTIAL / UNSUPPORTED / BLOCKED / RESEARCH_REQUIRED. Unsupported Bedrock build → refuse native attach, show "Xykell module unavailable on this Minecraft version.", launcher still manages versions/content. Missing/stale signatures → affected modules unload, core degraded, diagnostic names the symbol. Native crash → tombstone + redacted log, quarantine persisted, safe mode offered next launch (`[Disable Module] [Open Diagnostics] [Continue Safe Mode]`). Config corruption, storage-full, permission-denied: defined degraded paths, no silent loss.

## 7. Modes & safety gates
SAFE default (perf/HUD/visual/QoL). QOL → ADVANCED → EXPERIMENTAL each require explicit opt-in with warning. Advanced/Experimental modules ship disabled and can never self-enable.

## 8. Security (see LICENSES.md, SECURITY.md)
No credential/token/MS-password handling, no hidden telemetry/requests, no RCE, no arbitrary downloaded code, no unknown-APK installs, no Android-security bypasses, no backdoors. Signed releases + checksums, local-only backups, redacted logs, sandboxed scripts.

## 9. Honesty rule
Never "Implemented" without existing, tested implementation. Never invent APIs/offsets/signatures/internals. Unknown → [RESEARCH REQUIRED]. Impossible → [BLOCKED — PLATFORM LIMITATION]. Device tests claimed only when performed.

## 10. What is explicitly NOT built
Auth/DRM bypasses, server-compromise or DoS tooling, packet attacks, account/protection bypasses, copied proprietary code/assets/offsets/branding, fake placeholder modules.
