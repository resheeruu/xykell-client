# Milestone 1 — minimal bootable Xykell Core

Goal: prove the native core can **start, attach to the supported Bedrock build, render a minimal overlay, receive input, unload/recover cleanly, and survive a Minecraft restart**. No 100-module sprint before this passes.

## Scope (only this)
1. Android launcher shell: license check, version isolation, launch, load `.so`, safe-mode entry.
2. `libxykell-core.so`: lifecycle, module registry (empty-but-functional), typed event bus, JSON config + one profile (Default), redacted logging, crash handler + quarantine.
3. VersionAdapter: detect build, gate attach, "unavailable on this version" path.
4. Proof-of-concept: one overlay text (build + FPS), one touch input (tap toggles overlay), clean unload.
5. PerformanceManager skeleton: device profile readout only (no tuning yet).

## Acceptance criteria (all must be observed on-device, ARM64, legit Play copy)
- [ ] Cold launch → core attaches, overlay renders, no crash.
- [ ] Input tap toggles overlay; state persists across config reload.
- [ ] Unsupported Bedrock build → clean refusal with message, launcher usable.
- [ ] Forced module fault → quarantined, core alive, safe-mode offered next launch.
- [ ] Minecraft restart → re-attach idempotent, overlay/input restored.
- [ ] Crash produces redacted log; no tokens/paths leak.
- [ ] Corrupt config → backed up, defaults regenerated, logged.

## Out of scope for M1
ClickGUI, HUD editor, all feature modules, MITM backend, cosmetics, updater auto-install, Play release.

## Tests (Phase 19 subset for M1)
Module load/unload, config corrupt-recovery, profile round-trip, version-gate matrix, quarantine/recovery, touch-toggle. Host-side unit tests where possible; on-device checklist for the rest. Nothing marked passed without observed output.

## Exit
M1 exits when all boxes above are checked on a real device. Then — and only then — HUD modules (FPS/CPS/coords/keystrokes) + ClickGUI skeleton.
