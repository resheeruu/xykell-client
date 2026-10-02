# Milestone 1 (Minimal Bootable Core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove Xykell Core starts, attaches to the supported Bedrock build, renders a minimal overlay, receives input, unloads/recovers cleanly, and survives a Minecraft restart — before any feature modules are built.

**Architecture:** Kotlin/Java launcher shell (license check, isolation, launch, `.so` load, safe mode) + portable C++ core (`libxykell-core.so`, registry, event bus, JSON config, VersionAdapter, redacted logging, quarantine) + one proof overlay module. Host-testable logic stays platform-free; Android-only attach code is behind a verified toolchain gate.

**Tech Stack:** C++17 (core, CMake), Kotlin/Java + Gradle (launcher shell), JSON config with schema, lightest host test runner available (Task 0 decides — no heavy downloads without asking).

**Spec:** `docs/MILESTONE-1.md` (acceptance criteria); `docs/ARCHITECTURE.md`; `docs/ANDROID-NATIVE.md`; `docs/BEDROCK-COMPATIBILITY.md`.

## Global Constraints

- Android 9 (API 28)+, ARM64 (arm64-v8a) first; legitimate Play copy required, never ship game binaries.
- No auth/DRM/anti-cheat/platform-security bypasses, no credential/token handling, no hidden telemetry/requests.
- Every module independently toggleable; a faulting module is quarantined and must never crash the core.
- Unsupported Bedrock build fails closed with "Xykell module unavailable on this Minecraft version." — never a crash.
- Crash logs and diagnostics redact tokens/paths/IDs; corrupt config is backed up, defaults regenerated, logged.
- Phone constraints: lightweight deps only, no giant SDKs without asking, incremental builds.
- Nothing marked passed without observed output (on-device where host testing cannot reach).

## Review Focus

- Bedrock build newer than compat data at attach time → clean refusal, launcher still usable (Task 3 test).
- Module throws during event dispatch → dispatch continues, offender quarantined with counter (Task 2 test).
- Config file corrupt or schema-mismatched → backup + defaults + log, no crash (Task 4 test).
- Storage full or permission denied on config write → explicit degraded error, in-memory defaults kept, no silent loss (Task 4 test).
- Minecraft process restart mid-session → re-attach idempotent, overlay/input state restored from config (Task 5 on-device checklist).

---

### Task 0: Toolchain & baseline verification (gate)

**Files:**
- Create: `docs/TOOLCHAIN.md`
- Test: n/a (evidence-gathering; output is the doc)

**Interfaces:**
- Consumes: nothing
- Produces: pinned NDK/CMake/SDK/Gradle/JDK versions + on-device Bedrock build + protocol, consumed by Tasks 1–6 as exact values.

- [ ] **Step 1: Record host toolchain actually present** — `java -version`, Android SDK/NDK/CMake presence and versions, Gradle availability, free disk. Write exact versions into `docs/TOOLCHAIN.md`; mark anything missing as `[BLOCKED]` with the install size before downloading anything.
- [ ] **Step 2: Record on-device Bedrock facts** — installed build, version code, ABI, protocol; compare against `docs/BEDROCK-COMPATIBILITY.md` baseline and update that file if stale.
- [ ] **Step 3: Decide host test runner** — lightest option that runs here (e.g. plain `ctest`/shell asserts if GoogleTest download is heavy); record the exact command in `docs/TOOLCHAIN.md`. All later tasks use it.
- [ ] **Step 4: Commit** — `git add docs/TOOLCHAIN.md docs/BEDROCK-COMPATIBILITY.md && git commit -m "chore: pin M1 toolchain and Bedrock baseline from observed environment"`.

### Task 1: Core lifecycle + module registry

**Files:**
- Create: `client/core/registry.h`, `client/core/registry.cpp`, `client/core/lifecycle.h`
- Test: `tests/unit/test_registry.cpp` (runner from Task 0)

**Interfaces:**
- Consumes: nothing
- Produces: `Registry::load/unload/enable/disable/list/quarantine`, `Module` struct (id, version, states: loaded/enabled/quarantined), consumed by Tasks 2–5.

- [ ] **Step 1: Write the failing test** — load two fake modules, disable one, fault one (throw on enable), assert: faulty module lands in `quarantine()` list, registry still lists the healthy one as enabled, no exception escapes `Registry::enable`.
- [ ] **Step 2: Run test to verify it fails** — Run: runner command from Task 0. Expected: FAIL (files do not exist).
- [ ] **Step 3: Implement `Registry` + `Module` lifecycle in `client/core/registry.{h,cpp}`** — per-module try/catch boundary inside `enable/disable/dispatch`; quarantine records module id + reason string.
- [ ] **Step 4: Run test to verify it passes** — same command. Expected: PASS.
- [ ] **Step 5: Commit** — `git add client/core tests/unit && git commit -m "feat: core module registry with quarantine boundary"`.

### Task 2: Typed event bus

**Files:**
- Create: `client/events/bus.h`, `client/events/bus.cpp`
- Test: `tests/unit/test_bus.cpp`

**Interfaces:**
- Consumes: `Module` id from Task 1 for subscriber ownership.
- Produces: `Bus::subscribe(event, moduleId, fn)`, `Bus::publish(event)`, `Bus::unsubscribeModule(moduleId)`, dropped-event counter; consumed by Tasks 5–6.

- [ ] **Step 1: Write the failing test** — subscribe two modules to `frame` event, one throws; publish; assert: other subscriber still ran, thrower auto-quarantined via Registry hook, `droppedEventsFor(moduleId)` counter incremented, `unsubscribeModule` stops delivery.
- [ ] **Step 2: Run test to verify it fails** — Expected: FAIL.
- [ ] **Step 3: Implement `Bus` in `client/events/bus.{h,cpp}`** — snapshot subscriber list before dispatch (safe against unsubscribe-during-publish); exceptions routed to quarantine, never propagated.
- [ ] **Step 4: Run test to verify it passes** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: typed event bus with fault isolation"`.

### Task 3: VersionAdapter (pure logic, host-testable)

**Files:**
- Create: `client/compatibility/adapter.h`, `client/compatibility/adapter.cpp`, `client/compatibility/supported.json` (seed from Task 0 Bedrock facts)
- Test: `tests/unit/test_adapter.cpp`

**Interfaces:**
- Consumes: nothing
- Produces: `Adapter::check(build) -> {SUPPORTED, UNSUPPORTED, PARTIAL(missingFeatures[])}`, consumed by Task 5 attach gate.

- [ ] **Step 1: Write the failing test** — supported build → SUPPORTED; unknown newer build → UNSUPPORTED; build missing one feature → PARTIAL naming that feature; assert no throw on malformed JSON (returns UNSUPPORTED + reason).
- [ ] **Step 2: Run test to verify it fails** — Expected: FAIL.
- [ ] **Step 3: Implement `Adapter` + seed `supported.json`** — JSON-driven table, no hard-coded versions in code.
- [ ] **Step 4: Run test to verify it passes** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: VersionAdapter with JSON-driven support table"`.

### Task 4: JSON config + Default profile (corruption-safe)

**Files:**
- Create: `client/config/store.h`, `client/config/store.cpp`, `client/profiles/default.json`
- Test: `tests/unit/test_config.cpp` (+ `tests/configuration/` fixtures: valid, corrupt, schema-mismatched JSON)

**Interfaces:**
- Consumes: nothing (path injected for host tests).
- Produces: `Store::load(path)`, `Store::save()`, `Store::resetToDefaults()` (backs up bad file as `<name>.bad.<timestamp>`), consumed by Task 5.

- [ ] **Step 1: Write the failing test** — load valid fixture → values present; load corrupt fixture → returns defaults AND backup file exists AND redacted log line emitted; save with unwritable path → explicit error, in-memory values intact.
- [ ] **Step 2: Run test to verify it fails** — Expected: FAIL.
- [ ] **Step 3: Implement `Store` + `default.json`** — schema version field checked first; secrets-redacting logger stub (no real secrets exist yet; assert the redaction function masks a `token`-keyed value in test).
- [ ] **Step 4: Run test to verify it passes** — Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: corruption-safe JSON config and Default profile"`.

### Task 5: Launcher shell + attach gate + proof overlay (on-device)

**Files:**
- Create: `launcher/android/` minimal Gradle shell (license check, isolation flag, load `.so`, safe-mode entry), `client/core/entry.cpp` (attach/detach), `modules/hud/proof_overlay.cpp` (one text line: build + FPS; tap toggles)
- Test: `tests/compatibility/attach_checklist.md` (manual on-device runbook; host CI only asserts the files/docs exist)

**Interfaces:**
- Consumes: Registry (T1), Bus (T2), Adapter (T3), Store (T4).
- Produces: observable M1 acceptance criteria; consumed by Task 6.

- [ ] **Step 1: Write the runbook first** — `tests/compatibility/attach_checklist.md` with one checkable line per M1 acceptance criterion (cold-launch attach, tap-toggle + persist, unsupported-build refusal, fault quarantine + safe-mode offer, restart re-attach, redacted crash log, corrupt-config recovery).
- [ ] **Step 2: Implement attach gate** — `entry.cpp`: detect build → `Adapter::check` → UNSUPPORTED/PARTIAL refuses or degrades with the exact user message from the spec; never attach blind.
- [ ] **Step 3: Implement launcher shell** — license presence check, version-isolation launch, `.so` load, safe-mode (core only) entry point. No game-memory code in Java/Kotlin beyond loading.
- [ ] **Step 4: Implement proof overlay** — single overlay text + tap-to-toggle wired through Bus input event and persisted in Store. No ClickGUI, no other HUD.
- [ ] **Step 5: Run the on-device runbook** — check each box with observed output; any failure becomes a `fix/*` commit, not a skipped box. Record device + build + results in the runbook.
- [ ] **Step 6: Commit** — `git commit -m "feat: M1 launcher shell, attach gate, proof overlay"`.

### Task 6: Crash handler + diagnostics + M1 exit audit

**Files:**
- Create: `client/core/crash.h`, `client/core/crash.cpp`, `client/telemetry/diag.cpp` (redacted log writer)
- Test: `tests/unit/test_crash.cpp` (fault injection → quarantine persisted, redaction asserted) + M1 exit audit against `docs/MILESTONE-1.md`

**Interfaces:**
- Consumes: Registry quarantine (T1), Store (T4).
- Produces: M1 exit decision.

- [ ] **Step 1: Write the failing test** — simulate native fault in a module; assert: quarantine file persisted, next-boot reads it and offers safe mode, written log contains no secret-pattern strings.
- [ ] **Step 2: Run test to verify it fails** — Expected: FAIL.
- [ ] **Step 3: Implement crash handler + redacted diagnostics.**
- [ ] **Step 4: Run test to verify it passes** — Expected: PASS.
- [ ] **Step 5: M1 exit audit** — walk every `docs/MILESTONE-1.md` checkbox with evidence links (test output or runbook entries); unchecked boxes stay open, no exceptions.
- [ ] **Step 6: Commit** — `git commit -m "feat: crash handling with redacted diagnostics"`.
