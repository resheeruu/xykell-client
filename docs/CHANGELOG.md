# Changelog

## [Unreleased]
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
