# Levi removal audit (2026-10-02, HEAD `92478bc` + working tree)

Target final: `Xykell Launcher → Xykell Runtime → Minecraft Bedrock`
(zero Levi/Preloader in production).

## Every Levi dependency
| # | Location | What | Why it exists | Xykell replacement | Status |
|---|---|---|---|---|---|
| 1 | `native/src/xykell.cpp` (`#include <pl/Mod.hpp>`, `PL_REGISTER_MOD`, `ModContext` dirs) | load entry + storage roots | only verified in-process entry mechanism | Xykell loader (needs signature-derived attach — BLOCKED, no mechanism) | LEGACY, required |
| 2 | `native/src/xykell_portal_preloader.cpp` (pl/ModMenu, pl/Input, pl/log) | menu/input/log backend behind `portal/` seams | only verified overlay/input path | portal interfaces exist; standalone backend needs render+input targets — BLOCKED | LEGACY, required |
| 3 | `third_party/preloader-android` (vendored headers, Apache-2.0) | SDK for #1–2 | compile-time dependency | none possible until loader exists | LEGACY reference; keep, labeled |
| 4 | CI `XYKELL_LINK_PRELOADER=ON` + FetchContent | link preloader runtime (NDK ld forbids undef syms) | NDK toolchain requirement | same as #1 | LEGACY, build-only |
| 5 | `LaunchExecutor` → `org.levimc.launcher` MainActivity | PLAY handoff | only verified cross-app launch | staged PLAY pipeline ending in STANDALONE RUNTIME NOT READY; handoff retained ONLY as labeled legacy action | migrating this batch |
| 6 | `AndroidManifest <queries>` for `org.levimc.launcher` | detect Levi for #5 | supports legacy handoff | remove when handoff removed | LEGACY |
| 7 | Docs calling Levi "foundation" | historical framing | earlier architecture decision | reword to migration framing | this batch |

## What is already Levi-free (Xykell-owned, pure)
Config, profiles, CrashGuard, event bus, module registry/manager, JSON,
file utils, VersionAdapter table, sigscan infra, probe/gate, engines,
friends/servers/waypoints/notifications, themes, HUD/ClickGUI models,
renderer, input router, JNI bridge shape, launcher shell + SAF + registry
browser. None import Levi/Preloader symbols.

## Migration order (per §42/43)
1. Detection fix + states (this batch) — no Levi involvement at all.
2. Portal seams (DONE in tree: menu/input/log behind `portal/`).
3. Staged PLAY with fail-closed loader stage (this batch).
4. Loader research (signature pipeline per build) — long pole, BLOCKED.
5. Standalone menu/input/render backends — BLOCKED on #4.
6. Delete handoff + `<queries>` Levi entry + preloader vendoring — only AFTER
   #4–5 proven on-device. Until then: LEGACY COMPATIBILITY MODE, labeled.

## Licensing
Preloader headers Apache-2.0 (retain notices via provenance.md). No GPL
material in tree. Nothing copied from Levi app (only manifest strings read
+ Apache-2.0 IntentHandler mechanism studied, not reproduced).

## Test strategy
Host suites for portal fakes, detection verdicts, staged PLAY decisions;
CI green required; device proof via runbook (human).
