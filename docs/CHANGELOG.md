# Changelog

## [Unreleased]
- Standalone migration: LEVI-REMOVAL-AUDIT, portal seams (menu/overlay/
  input/log) with labeled preloader backend, detection states, staged PLAY.
- Full universe: 255-entry registry (settings schemas + risk levels for all),
  generated MODULE-CATALOG, real local systems (friends, notifications,
  server profiles, waypoints, +2 themes), Lunar-101 + Flarial-52 coverage
  research, new docs (FEATURES/MODULES map, PERFORMANCE, STORAGE, PRIVACY,
  MODULE-SDK, REFERENCE-COVERAGE). 18/18 suites PASS.
- PLAY handoff: Levi IntentHandler source read (no external launch action —
  bare URIs rejected), LaunchDecider/Executor (pre-checks + verified
  MainActivity intent, honest outcomes), launcher.play PARTIAL with evidence.
- Runtime enablement: Levi manifest census (no external launch API — PLAY
  verdict stands), JNI version verdict (shared adapter) + real installed-MC
  display on Versions/Home, no fake launch path.
- Registry v2: `xykell.*` ids, 15 categories, 225 entries (deduped, full
  universe incl. server/proxy/launcher/automation), generated
  MODULE-CATALOG.md, new docs (PERFORMANCE, STORAGE, PRIVACY, MODULE-SDK).
  17/17 suites PASS.
- Batch 8: XYKELL_RUNTIME_ACTIVE marker (load-only, cleared on unload),
  proof banner overlay, host tests run in CI (g++, TMPDIR-portable), device
  test doc + result template. 17/17 suites PASS.
- Batch 7: symbol census of 1.26.45.1 libminecraftpe (90,545 defined dynsyms,
  zero game-namespace exports — signature derivation is the only path), JNI
  bridge (shared ProfileManager in app, native-backed Profiles screen),
  Levi-dup registry notes, device verification doc, Quick Launch verdict.
  16/16 suites PASS.
- Batch 6: load checkpoints (12 stages, exact failure reports), signature
  pipeline infra (pattern/scan/validate, empty by design), Quick
  Launch verdict (URIs address the game, not launcher config — PLAY stays
  disabled), launcher SAF export-import, CI size audit, INCOMPATIBLE state.
  16/16 suites PASS. New docs: SIGNATURE-PIPELINE, SERVICES,
  LAUNCHER-INTEGRATION, MODULE-IMPLEMENTATION-STATUS.
- Batch 5: runtime diagnostics ModMenu module (probe report text), 1.26.45
  known-good (Levi v1.5.17 evidence — no version mismatch), tick-mechanism
  finding (game hooks + per-version signatures; nothing copied), Quick Launch
  URI lead for PLAY, device runbook, version/capability/Levi doc updates.
  15/15 suites PASS (probe covers 21 caps + report format).
- Batch 4: XykellRuntimeProbe (21 caps) + capability gate, registry
  `requires[]` + schema keys (name/description/settings/platforms/
  version_constraints/evidence), engines (target/move/budget/perf, gated),
  audit script (fake-SUPPORTED gate), CI registry checks, launcher
  Worlds/Packs/Servers screens, MC 1.26.45.1 device baseline, new docs
  (INVENTORY, CAPABILITIES, SECURITY, SCRIPTING/PROXY-DESIGN, LICENSE-MATRIX).
  15/15 host suites PASS.
- Batch 3: ClickGUI runtime binding (GuiController toggle honesty, ModMenu
  open/close), InputRouter (closed=M1 path, open=GUI consume), HudRenderer
  (theme colors, real taps/module states, `--` rule), editor clamp/cancel,
  profile layout IO, recovery module + safe-mode flow, launcher registry
  browser (build-time asset, search + statuses), size audit + strip at
  package time. 13/13 host suites PASS.
- Batch 2: real Config (atomic/corrupt-recovery/migrate), Profiles (6 builtins,
  full CRUD + import/export), CrashGuard (threshold quarantine, safe mode,
  persistent), ClickGUI model from registry JSON, HUD framework (`--` rule),
  themes. 10/10 host unit suites PASS. `.so` 171,912 → 445,816 bytes.
  Storage roots from verified `ModContext` dirs only. No gameplay modules.
- M1.5: standalone launcher shell (`app/`, 6 screens, honest NOT WIRED states) + CI APK workflow (`android-release.yml`: NDK r28c native rebuild, Gradle debug APK, artifact). Phone-side APK packaging proven impossible (stock `aapt2` is x86-64). New docs: RELEASE, TESTING, M1.5-RESULTS. Retired empty `launcher/` placeholders.
- Master-spec alignment: Levi-first foundation, capability classes, module API, M1 proof UI, expanded doc set (LEVI-INTEGRATION, MODULE-SYSTEM, UI-SYSTEM, NETWORK-ARCHITECTURE, SCRIPTING, PERFORMANCE, LICENSES, ROADMAP).
- Research depth: Lunar Proxy (closed proxy, 101 modules), WClient (GPL-3.0 legacy archive), BedrockTools (primary native reference; license discrepancy noted), Apollon (closed APK, authoritative source still [REQUIRES-RESEARCH]).

## 2026-10-02
- Approved Approach-A native spec (architecture, android-native, compatibility, milestone-1) + M1 implementation plan.
- Phase 0 research baseline + repo skeleton (docs only, no modules).
