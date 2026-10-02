# Batch 5 device runbook (human-executed; Termux cannot tap/install)

## 0. Fetch the artifacts (on the phone browser or PC)
- Xykell levipack: repo `dist/xykell.levipack` (copy to Download if built locally).
- LeviLauncher v1.5.25 APK: GitHub `LiteLDev/LeviLaunchroid` releases
  (59,144,383 B, sha256 `09747ae1…954cbc` — verified copy in `/sdcard/Download/`).
- XykellClient-debug APK: CI artifact `XykellClient-debug` (latest sha in `docs/M1.5-RESULTS.md`).

## 1. Install (Android UI — `pm install` is blocked, do not retry)
1. Install LeviLauncher APK (allow unknown sources, grant files permission).
2. Confirm official Minecraft 1.26.45.1 is detected (matches Levi's 1.26.45 line, v1.5.17+).
3. Install XykellClient-debug APK (launcher shell smoke test).

## 2. Launcher smoke test
Open Xykell Client → all 9 screens render → PLAY shows NOT WIRED → Modules
screen lists 192 entries with status counts → no crash on back/home/rotate.

## 3. Levi load test
1. In Levi: enable version isolation → Mods → import `xykell.levipack` → enable Xykell.
2. Launch Minecraft through Levi.
3. Open Mod Menu → expect: `Xykell Core`, `Xykell HUD`, `Xykell ClickGUI`,
   `Xykell Runtime Diagnostics` (plus `Xykell Recovery` only if safe mode).
4. Record which of these appear. Missing ones = failing boundary (report it).

## 4. Overlay + diagnostics
1. Expect top-left overlay: `XYKELL 0.1.0-m1 | mc=unknown [PARTIAL]`,
   `FPS: --`, `XYZ: n/a`, `taps: 0`, `modules: x/y on`.
2. Open `Xykell Runtime Diagnostics` → copy every `CAP=STATE` line.
3. Tap the screen → `taps:` increments, normal touch still works.

## 5. ClickGUI + toggles
1. Toggle `Xykell ClickGUI` ON → tap a RESEARCH_REQUIRED row (e.g. combat.kill_aura):
   nothing enables (expected). Toggle `client.core` row equivalent if present.
2. Header tap closes the GUI; taps route to game again.

## 6. Safe mode
Kill-switch test (only if stable first): rename `crashguard.json` content to
`{"safeMode":true,...}`? NO — do not hand-edit; instead report any natural
safe-mode entry with its reason text verbatim.

## 7. Logs + version
1. Record exact Bedrock version from Settings (expect 1.26.45.1).
2. `adb logcat` unavailable here — report visible behavior + screenshots.
3. Report crashes with: what you tapped, what was on screen, what survived restart.

## 8. Report format
Paste results per step: PASS/FAIL + verbatim text. FAIL = the boundary that
failed (APK install / Levi launch / mod import / menu missing / overlay
absent / crash), never just "doesn't work".
