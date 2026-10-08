# Xykell full-client gap analysis (2026-10-02, against HEAD `0a9688b`)

> HISTORICAL SNAPSHOT. Much of this was written at Phase 0/1 and is now
> superseded: current truth is `registry/features.json` (258 entries,
> 52 PARTIAL) + `docs/MODULE-CATALOG.md` + `docs/REFERENCE-COVERAGE.md`.
> Since then: 35 host unit suites + typecheck gate, scripting runtime,
> launcher screens, HUD editor, launcher servers/probes, diagnostics,
> relay stack, and local friends all exist. Sections below keep their
> original wording as the record of that date.

Method: tree + headers + CI logs inspected. Statuses: SUPPORTED / PARTIAL /
UNSUPPORTED / BLOCKED / RESEARCH_REQUIRED / NOT_IMPLEMENTED.

## 1. Existing features (verified, do not regress)
- Native `libxykell.so` (127,448 B, ARM64, `PLGetModRegistration` exported):
  Core init/shutdown/identity/capabilities/safeMode; table-driven VersionAdapter
  (honest PARTIAL on unknown); Mod Menu module (Enabled/SafeMode/DebugLogging);
  HUD proof (static overlay text + tap counter via verified `submitDrawCommands`
  + `registerTouchCallback`); clean unload path.
- Launcher shell `app/`: 6 screens, real `Build` diagnostics, disabled PLAY +
  persistent NOT WIRED, zero fake backends.
- CI green: NDK r28c native rebuild + Gradle debug APK (`XykellClient-debug`,
  8,386,714 B, sha256 recorded in M1.5-RESULTS.md), APK-extracted `.so` verified.
- Docs: architecture, Levi integration, licenses, module/UI/network/scripting/
  performance specs, M1 plan, M1+M1.5 results, roadmap.

## 2. Missing architecture
EventBus, runtime ModuleManager/registry, native Config/Profile managers,
Hook/Patch/Render/World/Player/Network managers, CrashGuard/quarantine
persistence, machine-readable feature registry. (This batch implements:
registry JSON, EventBus, ModuleManager + host tests.)

## 3. Missing modules
Everything in §§6–10 except the M1 proofs: all combat/movement/player/world/
visual/HUD modules are RESEARCH_REQUIRED or NOT_IMPLEMENTED. No Bedrock
runtime source exists yet for: frame ticks (live FPS), player position
(coords), entity/world data (ESP/XRay/waypoints), movement/combat automation.
These need signature/offset pipeline work per-version — the largest risk.

## 4. Missing launcher capabilities
PLAY wiring (needs verified Levi launch path — no invented intents),
version scan/manager, worlds, resource packs, shaders/materials, accounts,
server browser, diagnostics screen, update/manager backend, safe-mode action.

## 5. Missing UI
In-game ClickGUI (only Levi Mod Menu entries exist), HUD editor
(drag/resize/profiles), themes, search/favorites/recent, touchbind editor.

## 6. Missing rendering capabilities
No verified Bedrock render path beyond ModMenu `DrawCommand`s. No hook-based
render access, no shader/material pipeline, no overlay font control.
Any ESP/tracer/zoom work is BLOCKED until hook + signature research lands.

## 7. Missing network capabilities
Pinned preloader 0.2.3 headers expose NO packet API (verified file list:
Mod/ModMenu/Input/Config/memory/*). Packet observe/inspect/log/filter/modify
is therefore RESEARCH_REQUIRED at minimum; proxy/relay mode is NOT_IMPLEMENTED
by design (native client must work without it).

## 8. Missing scripting capabilities
No runtime, no API, no sandbox, no manager. DESIGN ONLY (docs/SCRIPTING.md).
Lua 5.4.x is a stated preference, not a decision — engine choice needs a
license/size review before any code.

## 9. Missing version support (Batch 4 update)
Device baseline RESOLVED: MC 1.26.45.1 on this phone (APK evidence).
Still missing: in-process version string, per-version capability tables,
signature/offset pipeline. Floor policy (≥1.21.80) inherited from Levi.

## 11. License/research gaps (Batch 4 update)
- Apollon: still no authoritative public source → APOLLON_SOURCE_UNVERIFIED.
- BedrockTools license discrepancy stands (treated as GPL-3.0).
- New: `docs/THIRD-PARTY-LICENSE-MATRIX.md` is now the per-file ledger.
- Reference→capability mapping added below (§12b):

| Reference feature | Xykell equivalent | Required capability | Status | Evidence |
|---|---|---|---|---|
| Lunar KillAura/AutoCrystal | combat.* (registry) | FRAME+PLAYER+ENTITY | RESEARCH_REQUIRED | no sources in SDK |
| Lunar Fly/Speed | movement.* | FRAME+PLAYER | RESEARCH_REQUIRED | same |
| Lunar Xray/ESP | world.*/visual.* | FRAME+WORLD(+ENTITY) | RESEARCH_REQUIRED | same |
| Lunar ClickGUI | Xykell ClickGUI model | OVERLAY_DELIVERY | PARTIAL | ModMenu API builds |
| Atlas FPS unlock/Zoom/waypoints | performance/visual/world | FRAME/WORLD/PLAYER | RESEARCH_REQUIRED | no frame/player/world sources |
| Flarial scripts | scripting.* | SCRIPTING | NOT_IMPLEMENTED | design only |
| WClient/Nova relay | network/packet, proxy | PACKET | BLOCKED | no packet API in headers |
| Apollon movement/combat | movement/combat.* | FRAME+PLAYER | RESEARCH_REQUIRED | source unverified |

## 10. Missing tests
Zero unit tests. No CI coverage of native logic. Device runbooks exist
(M1-RESULTS) but no device results returned yet. (This batch adds the first
host unit tests: core, adapter, event bus, module manager, registry schema.)

## 11. License/research gaps
- Apollon: no authoritative public source — features attributed to it stay
  RESEARCH_REQUIRED; never implement from mirrors/reposts.
- BedrockTools: README says GPL-3.0, badge says MIT — treated as GPL-3.0.
- WClient/Nova/Xelo: GPL-3.0 archives — behavior ideas only, license boundary kept.
- Lunar Proxy/Atlas/Flarial: closed — catalog/UX inspiration only.
- THIRD-PARTY.md still to be written (this batch).

## 12. Implementation dependency graph (build order)
1. Feature registry (JSON) → 2. EventBus → 3. ModuleManager →
4. Config/Profile store → 5. VersionAdapter runtime source →
6. CrashGuard/quarantine → 7. ClickGUI → 8. HUD engine →
9. Render abstraction → 10. Input abstraction → 11. Network abstraction →
12. Script system → 13. Launcher/version manager → 14. Atlas QoL →
15. Visual → 16. Player/World → 17. Movement → 18. Combat → 19. Proxy mode →
20. Performance pass → 21. Full integration → 22. Device verification → 23. Release.
Rule: a layer ships only on verified capabilities of the layer below; anything
else stays RESEARCH_REQUIRED in the registry — never a fake toggle.
