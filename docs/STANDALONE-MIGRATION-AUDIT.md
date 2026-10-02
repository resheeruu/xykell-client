# Standalone migration audit (2026-10-02, HEAD `92478bc`)

Target: `Xykell Launcher → Xykell Runtime → Minecraft Bedrock`
(no Levi/Preloader in final production).

## Current architecture
`app/` (launcher shell, JNI bridge to shared ProfileManager) +
`native/` (game module: core, stores, GUI/HUD models, engines) loaded by
Levi preloader as `preload-native` levipack.

## Levi/Preloader dependencies (exact, all in `native/`)
- `xykell.cpp`: `pl/Mod.hpp` (PL_REGISTER_MOD, ModContext dirs) — load entry.
- `xykell_menu.cpp`, `xykell_gui_host.cpp`: `pl/ModMenu.hpp`
  (ModuleBuilder/registerModule/unregisterModule/submitDrawCommands).
- `xykell_hud.cpp`: `pl/Input.hpp` (registerTouchCallback) + ModMenu draw.
- Build: FetchContent preloader lib in CI (`XYKELL_LINK_PRELOADER=ON`);
  vendored headers `third_party/preloader-android` (Apache-2.0).
- Launcher PLAY: explicit intent to Levi MainActivity (verified exported).
Nothing else touches Levi: config/profiles/bus/registry/engines/themes/
friends/servers/waypoints/notifications are 100% Xykell-owned pure C++.

## Xykell-owned components (no migration needed)
All of the above pure systems + JSON parser, file utils, CrashGuard,
VersionAdapter table, sigscan infra, runtime probe/gate, JNI bridge shape.

## Components requiring replacement for standalone mode
1. Load entry (PL_REGISTER_MOD → Xykell loader establishing process context
   + lib discovery + init). BLOCKED: needs signature-derived attach or a
   loader with equivalent privileges — neither exists. This is THE blocker.
2. Menu/input/draw backend (pl/ModMenu, pl/Input) → Xykell portal with
   preloader backend now, standalone backend later.
3. PLAY path (Levi intent → Xykell-owned launch of configured version).
   BLOCKED on the same loader question.

## Migration order
1. Portal abstraction (this batch): Xykell-owned menu/input/log interfaces,
   preloader backend = "LEGACY COMPATIBILITY MODE" (labeled).
2. Detection fix (this batch): `<queries>` + states + diagnostics.
3. Launcher platform: versions/content/accounts/SAF (incremental; this batch:
   Accounts screen + diagnostics depth).
4. Loader research (signature pipeline per build) — long pole, unchanged.
5. Only then: standalone load, render/input backends, PLAY direct.

## Risks
- Portal adds indirection; mitigated by keeping the preloader backend thin
  and tested (behavioral tests on recording fakes).
- JNI/storage: unchanged (separate sandboxes, export-file sync).
- No production behavior changes in this batch: preloader path stays default.

## Test strategy
Host suites for portal (fake backend records calls), detection verdicts,
continued 18/18 + new; CI unchanged + green required; device proof still
needs the human runbook.
