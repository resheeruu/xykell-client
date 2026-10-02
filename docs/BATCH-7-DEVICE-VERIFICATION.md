# Batch 7 device verification (human-executed)

## Pre-flight (record first)
DEVICE / ANDROID (`Settings → About`: e.g. Android 16, arm64-v8a) /
MINECRAFT (Settings in-game, expect 1.26.45.1) / LEVI (expect v1.5.25) /
PRELOADER (0.2.3) / XYKELL (APK version + sha from M1.5-RESULTS; levipack date).

## Install (Android UI only — `pm install` is SecurityBlocked, don't retry)
1. Install LeviLauncher v1.5.25 APK from Download.
2. Install XykellClient-debug APK (CI artifact) — launcher shell smoke test:
   9 screens, PLAY NOT WIRED, Modules lists 192 with counts.
3. In Levi: version isolation ON → import `xykell.levipack` → enable Xykell → Launch.

## Load checkpoints (overlay/log — expect in order)
PROCESS_STARTED → NATIVE_LIBRARY_LOADED → MOD_REGISTERED → CORE_INITIALIZED →
CONFIG_INITIALIZED → REGISTRY_INITIALIZED → DIAGNOSTICS_INITIALIZED →
HUD_INITIALIZED → INPUT_INITIALIZED → RUNTIME_PROBE_STARTED →
RUNTIME_PROBE_COMPLETED → READY. First missing/failed stage = the boundary.

## In-game checks
- ModMenu shows: Xykell Core, Xykell HUD, Xykell ClickGUI, Xykell Runtime Diagnostics.
- Diagnostics text: copy every `CAP=STATE` line (21 lines).
- Overlay: version line, `FPS: --`, `XYZ: n/a`, `taps: 0`, `modules: x/y on`.
- Tap screen: `taps:` increments, game touch unaffected.
- ClickGUI toggle ON → tap `combat.kill_aura` row: nothing enables (PASS = refusal).
- Safe mode: only if it triggers naturally — copy reason + quarantine list.

## Result template (paste back verbatim)
DEVICE: / ANDROID: / ABI: / MINECRAFT: / LEVI: / PRELOADER: / XYKELL:
Xykell loaded: / ModMenu: / Diagnostics: / HUD: / Input:
Load checkpoint reached:
Version: / Frame: / Player: / Entity: / World: / Camera: / Render: / Packet:
Crash: / Safe mode: /
Notes:
