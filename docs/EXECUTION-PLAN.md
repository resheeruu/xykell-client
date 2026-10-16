# Xykell Client — Execution Plan (audit)

**Audit date:** 2026-10-16
**Repository:** https://github.com/resheeruu/xykell-client (default branch `main`)
**Target:** Minecraft Bedrock Mobile on Android ARM64, touch-first, QoL/perf/HUD/visual core; combat/movement/world automation opt-in, OFF by default.
**Status of this document:** baseline execution plan produced from the audit; no feature batch implemented yet.

---

## 1. Current HEAD and working-tree state

**True current HEAD (origin/main):** `79da1667e2dc6d84ec7fcd6d9a34185d0aac6e8f`
(CI runs 38833781382 android-release + 38833781403 docs-check, both `success`, 2026-10-16T01:11:39Z.)

**Working copies — neither matches origin/main exactly:**

| Copy | SHA | Tracked tree | Untracked | Relation to origin/main |
|------|-----|--------------|-----------|-------------------------|
| `~/projects/xykell-client` (your stated expected path) | `ee2409a` | clean | none | **BEHIND origin/main by 7 commits → STALE** (missing the delivered module/relay/HUD work) |
| `~/w/wclient` | `b5a881d` | clean | `app/src/main/java/dev/xykell/client/runtime/modules/ServerProfileModules.kt` | **AHEAD of origin/main by 2 docs commits** (`b5a881d`, `0f893ca`); tracked source == origin/main |

**Required baseline action before any implementation:** reconcile the working copies to origin/main `79da166`.
- `~/projects/xykell-client`: `git pull --ff-only` (fast-forward, no local changes to lose).
- `~/w/wclient`: decide on the untracked `ServerProfileModules.kt` (commit it to a feature branch or discard) — it is **not** part of origin/current HEAD.

**Build:** `compileSdk 35`, `minSdk 28`, `targetSdk 35`, `versionName 0.8.1` / `versionCode 22`. AGP 8.5.2, Kotlin 1.9.24. Native C++ (`native/`) packaged as `lib/arm64-v8a/libxykell.so`; no device NDK on this phone, so Android assembly happens in CI only. No auth/DRM/anti-cheat bypasses (docs/ARCHITECTURE.md §8–10).

---

## 2. Current build and test evidence

**CI (all `success` at `79da166`):**
- `android-release`: JNI coverage audit → `scripts/registry/validate.py` → `scripts/audit/full-feature-audit.py` (gate: "No fake SUPPORTED entries") → 37 host C++ unit suites (`g++`) → JDK 17 + Android SDK + NDK r28c → builds native lib arm64-v8a (verifies `T PLGetModRegistration`) → stages into `jniLibs` → Gradle `:app:testDebugUnitTest` → `assembleDebug` → verifies APK contains `lib/arm64-v8a/libxykell.so` + real JNI symbols (`NativeSettings_settingsCatalog`, `NativeHud_setHudElement`, `NativeProfiles_setActive`, `NativeThemes_listThemes`, `NativeKeybinds_keybindSet`, `RuntimeStatus_nativeStart`, `Observations_nativeOfferPlayerMessage`) → size audit → uploads artifact `XykellClient-debug`.
- `docs-check`: `success`.
- `stage20-validation`: JVM unit tests, lab WebSocket tests (`npm test` in `lab/bedrock-websocket`), dependency resolution (OkHttp/okio/kotlin-stdlib), APK security inspection (exactly the 3 approved permissions; exactly 1 service, 0 receivers, no `BOOT_COMPLETED` receiver; dex endpoints limited to `ws://127.0.0.1:8765`).

**Local APK evidence (downloaded from CI, not built here):**
- `app-debug.apk`, 14,162,175 bytes, SHA256 `d44a843e069a96be53f541fb7594ef5607197b3cd52f8dc6b6ee1c72d2d535f1`.
- Contains `lib/arm64-v8a/libxykell.so` (12,197,432 bytes), `classes.dex`, `AndroidManifest.xml`, `resources.arsc`. Valid Android package — real CI build artifact, not a placeholder.

**Host unit tests (`scripts/test/run-unit.sh`):** 37/37 C++ suites pass (`test_core … test_feed_client`). Previously observed green on this host; CI re-runs them on every push.

**Kotlin / Android build:** NOT reproducible locally on this phone — no Gradle wrapper, no Android SDK/NDK, `root /` at 0 bytes, `/data/user/0` at ~1.8G free. Android assembly + instrumentation tests exist only in CI.

**M1 on-device evidence (docs/M1-RESULTS.md, 2026-10-02):** `libxykell.so` 127,448 bytes, ELF ARM aarch64, `T PLGetModRegistration` exported; `dist/xykell.levipack` packaged; Levi APK authentic (size + SHA256). **Device load/run: PENDING** (runbook in docs/M1-RESULTS.md; human must tap through install UI — `pm install` blocked on this host by `SecurityException` needing MANAGE_USERS).

**Registry:** `registry/features.json` = 258 entries: `0 SUPPORTED / 124 PARTIAL / 22 NOT_IMPLEMENTED / 15 DEVICE_LIMITED / 97 REFERENCE_ONLY`. CI rejects evidence-less `SUPPORTED`. `PARTIAL` = build-verified + host-tested, never run against a live session. `docs/MODULE-CATALOG.md` (generated) and `docs/MODULE-IMPLEMENTATION-STATUS.md` are machine truth.

---

## 3. Implemented functionality vs unverified functionality

**Implemented (PARTIAL — build + host tests pass; NOT live-session verified):**
- 53 pure, direction-scoped packet transforms, all host-tested:
  - combat (6): `velocity`, `knockback`, `auto_crit`, `afk_clicker`, `double_click`, `backtrack`
  - movement (8): `levitate`, `movement_correction`, `speed`, `no_fall`, `slow_falling`, `fly`, `motion_fly`, `jump_boost`
  - visual (18): `time_changer`, `fullbright`, `no_blindness`, `no_nausea`, `no_weather`, `weather_changer`, `particle_controls`, `fog_controls`, `esp`, `player_esp`, `entity_esp`, `item_esp`, `nametag`, `tracers`, `block_tracers`, `waypoints`, `chunk_borders`, `new_chunks`
  - world (5): `block_esp`, `block_tracer`, `chunk_borders`, `chunk_finder`, `new_chunks`
  - misc (5): `quick_drop`, `disabler`, `anti_weather`, `toggle_sprint`, `toggle_sneak`
  - player (7): `no_blindness`, `no_nausea`, `haste`, `no_fall`, `fast_eat`, `fast_interact`, `spam`
  - automation (4): `ghost`, `no_break_delay`, `auto_eat`, `auto_fish`
- Relay P2b (complete): `RakNetEndpoint` per leg, `BedrockBatch`/`BedrockHandshake`/`BedrockIdentity`, `RelayListener`, `RelaySessionDriver` terminating both Bedrock legs over UDP.
- HUD overlay: `TYPE_APPLICATION_OVERLAY` canvas at 4 Hz (`HudOverlayService`), reads the observation consumer (renders `--` until a value is actually observed).
- 9 delivered ids (docs/WHATS-NEW.md): `combat.backtrack`, `combat.afk_clicker`, `combat.double_click`, `misc.quick_drop`, `hud.tab_list`, `hud.server_info`, `hud.ip_display`, `player.death_position`, `player.spam` (`player.spam` = first authored packet).
- Launcher shell: Home/Versions/Modules/Profiles/Settings/About screens; backends NOT WIRED (M1.5).
- Native core: module registry/lifecycle, event bus, JSON config + profiles, CrashGuard (redacted + quarantine), VersionAdapter table, sigscan infra, runtime probe/gate, JNI bridge, render/input/network abstractions.
- Observed/decoded packets only: `SetHealth 0x2A`, `SetTime 0x0A`, `MovePlayer 0x13`, `Text 0x09`.

**Unverified / not live (0 SUPPORTED):**
- Every device-runtime capability: `Frame`, `Player`, `Entity`, `World`, `Camera`, `Render`, `Network/Packets` = `RESEARCH_REQUIRED` or `BLOCKED` on device (docs/RUNTIME-CAPABILITIES.md) — no SDK API; each needs a per-version signature-derivation pipeline that does not exist yet.
- In-process Bedrock version: `1.26.45.1` confirmed only via read-only APK string-pool (docs/DEVICE-TESTING.md); in-process source = `RESEARCH_REQUIRED`.
- Standalone loader (docs/STANDALONE-MIGRATION-AUDIT.md, docs/LEVI-REMOVAL-AUDIT.md): load entry `PL_REGISTER_MOD` → Xykell loader needs signature-derived attach; **no mechanism exists — this is THE blocker**. Levi preloader retained as LEGACY COMPATIBILITY MODE until proven otherwise.

---

## 4. Top critical blockers and dependencies

1. **Standalone loader (THE blocker).** No signature-derived attach / loader mechanism. Xykell currently runs on Levi preloader (`preload-native` levipack) behind `portal/` seams labeled LEGACY COMPATIBILITY MODE. Migration can only reach standalone load, render/input backends, and direct PLAY after the loader is proven (signature pipeline per Bedrock build — long pole).
2. **No device runtime verification.** `0 SUPPORTED`; no live Bedrock session round-trip; device install cannot be scripted on this host (`pm install` → `SecurityException`). Human device runbooks are the gate.
3. **No in-process Bedrock version source.** `1.26.45.1` known only from read-only APK string-pool; in-process detection is `RESEARCH_REQUIRED` (VersionAdapter cannot yet confirm the running build).
4. **Phone build constraints.** `root /` at 0 bytes; `/data/user/0` ~1.8G free; no Android SDK/NDK/Gradle locally → CI is the only Android build/test path. Do not download large toolchains here without asking.
5. **Bedrock renderer/input hooks.** `RENDER`/`PACKET` blocked; frame/player/entity/world need per-version signature derivation (docs/RUNTIME-CAPABILITIES.md §Batch 5). Xykell will not paste third-party signatures (GPL/provenance).

---

## 5. Ordered milestones with concrete deliverables

| Milestone | Goal | Concrete deliverable | Exit condition |
|-----------|------|----------------------|----------------|
| M0 | Baseline reconciliation | `docs/EXECUTION-PLAN.md` (this file); working copies synced to origin/main `79da166`; untracked `ServerProfileModules.kt` decided | `git status` clean on both copies at `79da166`; this doc committed |
| M1 | Reproducible build & host gate | `scripts/test/run-unit.sh` 37/37; `scripts/registry/validate.py` clean; `scripts/audit/full-feature-audit.py` "No fake SUPPORTED entries"; CI green at `79da166` | CI + local host suites green; registry audit clean |
| M2 | Human device verification of launcher + overlay | Install CI `XykellClient-debug` APK; open all 6 launcher screens; confirm PLAY disabled + "NOT WIRED" texts; no crash on back/home/rotate; HUD overlay draws (grants "display over other apps") | Observed device output (log/photo/transcription) per docs/DEVICE-TESTING.md runbook |
| M3 | Reproducible Bedrock 1.26.45.1 integration | Confirm in-process Minecraft version string via VersionAdapter on device | Device log shows running Bedrock = `1.26.45.1` |
| M4 | Minimal verifiable game-state + input proof | One observed packet (e.g. `MovePlayer 0x13`) reflected live in HUD; `player.spam` authored round-trip | HUD shows a live non-`--` value sourced from a relay-decoded packet during a session |
| M5 | HUD values backed by real observations | `fps`/`coordinates`/`tab_list` render live values (not `--`) from the observation consumer | Each HUD value tied to an observed packet; `--` only when unobserved |
| M6 | Stable action/cancellation/permission interfaces | Touch-automation cadence (`afk_clicker`/`double_click`) + overlay grant flow (`SYSTEM_ALERT_WINDOW`) + `canDrawOverlays` re-read on resume | Gesture dispatch host-tested; overlay starts/stops cleanly per grant state |
| M7 | Blueprint + mining tested independently | `AutoMine`/`TreeCapitator` scaffolds as REFERENCE_ONLY → decision: implement or keep blocked | Documented decision; no fake toggles |
| M8 | AI planning + recovery | Planner + safe-mode recovery paths host-tested | CrashGuard quarantine + safe-mode offer verified on host |
| M9 | Additional combat + visual features | Only after M4–M6 pass; gated on injection/knowledge-table availability | Each new id has host test + registry reason; none claimed SUPPORTED |
| M10 | Standalone migration + Levi removal | Loader proven on-device → direct PLAY → delete Levi handoff + `<queries>` + preloader vendoring | `PL_REGISTER_MOD` replaced by Xykell loader; Levi imports removed from Xykell-owned code |

**Ordering rule:** M10 (standalone/Levi removal) only after M4–M6 (loader + injection proven). No module claimed `SUPPORTED` without device evidence.

---

## 6. Acceptance tests per milestone

- M1: `run-unit.sh` exit 0 (37 suites); `validate.py` exit 0; `full-feature-audit.py` contains "No fake SUPPORTED entries."; CI `success` at `79da166`.
- M2: device log/photo/transcription showing all 6 screens open, PLAY disabled, overlay draws after `canDrawOverlays` grant, no crash on rotation/back/home.
- M3: device log line `minecraft version = 1.26.45.1` from in-process source (not string-pool).
- M4: HUD line changes during a live session and traces to `MovePlayer 0x13` / `player.spam` decode; round-trip encoder/decoder test green.
- M5: each HUD value renders live data; `--` rendered only when the relay has not observed the field.
- M6: `afk_clicker`/`double_click` tap cadence host-tested; overlay start/stop tied to `Settings.canDrawOverlays`; no auto-start, no exported components.
- M7–M10: per-id host test + documented registry reason; M10 requires loader success on device before any Levi deletion.

---

## 7. Required device-side checks and what the user must do

These cannot be performed by this agent (no device access, `pm install` blocked on this host, no ADB):

1. Install `XykellClient-debug.apk` (CI artifact `XykellClient-debug` from run 38833781382) — MIUI blocks CLI install; human taps through install UI.
2. Grant "Display over other apps" (`SYSTEM_ALERT_WINDOW`) for the overlay.
3. Open all 6 launcher screens; confirm PLAY disabled + "NOT WIRED" texts; no crash on back/home/rotate.
4. Start the HUD overlay from the HUD screen; run a Minecraft session; confirm live HUD values (not `--`).
5. Capture output (logcat/photo/transcription) per docs/BATCH-7-DEVICE-VERIFICATION.md and docs/BATCH-8-DEVICE-TEST.md.
6. Record exact Minecraft version from Settings → update the M3 baseline.

Rule (docs/DEVICE-TESTING.md): **BUILD VERIFIED ≠ RUNTIME VERIFIED.** Every runbook entry needs observed output before PASS.

---

## 8. Storage, toolchain, and CI constraints

- `root /` (824M) at 0 bytes — system partition, do not poke. `/data/user/0` ~1.8G free after clearing session temp + npm cache.
- No Gradle wrapper, no Android SDK/NDK, no local Chromium → Android build + E2E only in CI (`ubuntu-latest`, JDK 17, NDK r28c, Gradle 8.10.2).
- Phone-friendly rules: lightweight deps only, incremental builds, no giant downloads without asking (AGENTS.md).
- Disk-sensitive: do not download toolchains or caches here without confirming necessity and available space.

---

## 9. Rollback strategy and compatibility preservation

- Release signing uses the managed keystore (`~/.config/xykell/managed.keystore`, env overrides `XYKELL_KEYSTORE_*`), the same key as 0.2.x releases → in-place upgrade, no forced uninstall. Signing skipped (not failed) when keystore absent (debug-grade; sideload only, not for any store).
- Levi preloader retained as LEGACY COMPATIBILITY MODE until standalone loader proven on-device — no production behavior change in the migration batch.
- Per-module exception boundary + watchdog: a faulting module is quarantined, never crashes the client; safe mode offered next launch (`[Disable Module] [Open Diagnostics] [Continue Safe Mode]`).
- Config corruption / storage-full / permission-denied: defined degraded paths, no silent loss; corrupt config backed up, defaults regenerated.
- Git discipline: one concern per commit; `main` stays clean (`feature/*`, `fix/*`, `research/*` branches); no force-push / `reset --hard` / `clean -fd` without confirmation.

---

## 10. Exact next implementation task and affected files

**Next task (M0 → M1):** Reconcile working copies to origin/current HEAD `79da166`, then confirm the host gate.

Required user decision: confirm which working copy is canonical (`~/projects/xykell-client` must `git pull --ff-only`; `~/w/wclient` must resolve untracked `ServerProfileModules.kt`), or I will treat `origin/main 79da166` as the audit baseline and proceed from there.

**Affected files (baseline, no code change yet):**
- `docs/EXECUTION-PLAN.md` (this file — new)
- `~/projects/xykell-client` tracked tree: none until synced to `79da166`
- `~/w/wclient`: `app/src/main/java/dev/xykell/client/runtime/modules/ServerProfileModules.kt` (untracked — commit to feature branch or discard)

**First code task after sync (M4):** add one live observed-packet → HUD proof (e.g. wire `MovePlayer 0x13` decode to a HUD line) with a host test. Affected files would be `app/src/main/java/dev/xykell/client/runtime/relay/*`, `app/src/main/java/dev/xykell/client/runtime/modules/MovementModules.kt`, `app/src/main/java/dev/xykell/client/ui/HudOverlayLines.kt`, plus `app/src/test/java/.../MovementModulesTest.kt`.

**Measurable done for this plan:** CI green at `79da166`, host gate 37/37 + registry audit clean, `docs/EXECUTION-PLAN.md` committed, working copies at `79da166`, and the user has decided the canonical working copy. Nothing is `SUPPORTED` until a device session proves it.

---

*Evidence baseline: CI runs 38833781382/38833781403 at `79da166`; local APK SHA256 `d44a843e…`; registry 258 entries (0 SUPPORTED); M1-RESULTS on-device build evidence (device run PENDING). All status claims trace to `registry/features.json`, docs/RUNTIME-CAPABILITIES.md, docs/WHATS-NEW.md, docs/MODULE-CATALOG.md, docs/STANDALONE-MIGRATION-AUDIT.md, docs/LEVI-REMOVAL-AUDIT.md.*
