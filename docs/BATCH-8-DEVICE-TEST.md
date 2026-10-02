# Batch 8 device test (minimal human action, maximum information)

## What to install (Android UI only)
1. `XykellClient-debug` APK — CI artifact `XykellClient-debug` (sha in `docs/M1.5-RESULTS.md`).
2. `LeviLauncher-v1.5.25-release.apk` (59,144,383 B, sha256 verified).
3. `dist/xykell.levipack` — copy to Download, import in Levi Mods, enable Xykell.
4. Launch Bedrock 1.26.45.1 through Levi (version isolation ON).

## What to look for (in order)
1. ModMenu lists: Xykell Core, Xykell HUD, Xykell ClickGUI, Xykell Runtime Diagnostics.
2. Overlay shows the proof banner:
   `+------------------------------+`, `| XYKELL CLIENT RUNTIME ACTIVE |`,
   version line. (Banner exists ONLY if native code runs in-process.)
3. Diagnostics module text: copy all `CAP=STATE` lines (21).
4. Tap screen: `taps:` increments; game touch unaffected.
5. Version line still `mc=unknown` (expected — no in-process source yet).

## Result template (paste back)
XYKELL DEVICE TEST
Device: / Android: / ABI:
Minecraft: / Levi: / Preloader:
Xykell APK SHA: / Xykell package SHA:
Xykell visible: / ModMenu visible: / Diagnostics visible: / HUD visible: / Input visible:
Runtime Active: YES / NO
Checkpoint: / Last stage:
Minecraft version: BUILD / APK / RUNTIME / UNKNOWN
Frame: / Player: / Entity: / World: / Camera: / Render: / Packet:
Crash: / Safe mode:
Screenshot/log:
