# Xykell — Architecture (draft for review)

Status: DRAFT. Implementation of native layer starts only after approval.

## 1. Chosen shape
- `launcher/android` (Kotlin/Java, Gradle): own Play copy, version isolation, launch, load native modules, touch UI shell, Mod Menu host.
- `client/` (C++ preloader .so, arm64-v8a): core registry, event bus, config, modules, render/input/network abstractions, VersionAdapter, crash/diagnostics.
- `modules/` : one dir per category; each module = metadata + category + settings + lifecycle + compatibility + implementation. Independently toggleable; broken module must not crash client (isolate exceptions, per-module try/catch, watchdog).
- Fallbacks: MITM network backend (isolated, opt-in) + pack/scripting degraded mode.

## 2. Core responsibilities (Phase 2)
Module registry, lifecycle (load/enable/disable/unload), event bus (game/input/render/network/config), JSON config + profiles, keybind/touchbind, theme, version compat, logging (redacted), crash handling, diagnostics, update check (signed + checksum), permissions, feature flags, dependency guard, API/render/input/network abstractions.

## 3. Versioning
`VersionAdapter`: supported-version table, unsupported detection, per-feature availability, warning UI, graceful disable with message "Xykell module unavailable on this Minecraft version." No hard-coded single version. Signatures/offsets per release live in `client/compatibility/` + data files, never scattered.

## 4. Modes
SAFE (default: perf/HUD/visual/QoL) → QOL → ADVANCED (combat/movement/world automation, all OFF by default, explicit warning) → EXPERIMENTAL (native/packet research, isolated). Advanced/Experimental never auto-enable.

## 5. Touch-first UI (Phase 14/15)
Not a shrunk desktop ClickGUI. Large targets, swipe nav, quick-toggle panel, floating controls, one-handed mode, landscape optimization, UI scale, safe-area/notch support, controller + kb/mouse where available. Categories: Performance/HUD/Visual/Combat/Movement/Player/World/Utility/Network/Social/Cosmetics/Automation/Experimental/Settings. Search, favorites, recent, descriptions, sliders/toggles/dropdowns/color picker, keybind + touchbind editors, profile selector, import/export/reset.

## 6. Security (Phase 18, non-negotiable)
No credential/token collection, no MS password handling, no hidden telemetry/requests, no RCE, no arbitrary downloaded code, signed releases + checksums, safe updater, local config backups, redacted crash logs.

## 7. First milestone (Phase 24) — in order
repo → arch → build system → Android compat research → core → registry → events → config → profiles → touch UI → ClickGUI → FPS/CPS/coords/keystroke HUDs → perf manager → version detection → crash handling → first Android test build. No 100-module sprint before this lands.

## 8. Build strategy notes (Termux/phone, ~1.5G free)
Prefer lightweight deps, incremental/cached/modular builds, compressed assets, no giant SDKs unless required. NDK/CMake via Android Studio baseline per Levi docs; verify on-device before promising native dates. Never delete user data to free space.
