# M1 results (Task 6)

## Build evidence (observed on-device, Termux, 2026-10-02)
- `bash scripts/build/build-native.sh` → `libxykell.so` **127,448 bytes**,
  `ELF 64-bit LSB shared object, ARM aarch64, for Android`, `T PLGetModRegistration` (ENTRY-OK).
- Undefined refs limited to fmt/libc++/liblog/libc — all resolvable in a game process.
- `bash scripts/build/package-levipack.sh` → `dist/xykell.levipack` (127,673 bytes:
  `xykell/manifest.json` + `xykell/libxykell.so`). Kept out of Git (`*.levipack`).
- LeviLauncher-v1.5.25-release.apk: 59,144,383 bytes in `/sdcard/Download/`,
  **SHA256-OK** vs release digest `09747ae1…954cbc`.
- Storage: repo 1.6 MB; build/ 447 KB (ignored); APK 56 MB staged for install.

## Device runbook (for the human — Termux cannot tap through install UI)
1. Install `LeviLauncher-v1.5.25-release.apk` from Download (allow install, grant files permission).
2. Open LeviLauncher → verify official Minecraft detected → enable version isolation.
3. Import `dist/xykell.levipack` (copy to device-visible folder first if picker needs it) → enable Xykell.
4. Launch Minecraft through Levi → observe Mod Menu: expect modules
   `xykell-core` (Xykell Core) and `xykell-hud` (Xykell HUD M1 proof).
5. Observe overlay (top-left): `XYKELL 0.1.0-m1 | mc=...`, `FPS: -- ...`,
   `coords: n/a ...`, `taps: 0`. Tap the screen → `taps:` increments.
6. Toggle `xykell-hud` OFF → overlay clears. Toggle ON → overlay returns.
7. Toggle `safe_mode` / `debug_logging` in `xykell-core` → state applies (visible in menu).
8. Restart Minecraft → overlay + toggles return (persistence check).
9. Record exact Minecraft version from Settings → update Task 0.2 baseline.

## Results
| Check | Status |
|---|---|
| ARM64 build succeeds / entry exported | VERIFIED (above) |
| .levipack packaged per Levi layout | VERIFIED (above) |
| Levi APK authentic (size + sha256) | VERIFIED (above) |
| Levi loads Xykell / init / menu / HUD / taps / toggles / restart | PENDING DEVICE (runbook above) |
| Exact Bedrock version string | [RESEARCH REQUIRED] (read at step 9) |
| FPS live value / real coordinates | Honestly deferred: no verified frame-tick or player-position source (see hud.h); overlay shows `--`/`n/a` by design |

## Known deviations from Levi checklist
- Menu entries unregister at `unload()`, not `disable()` — the toggle itself is the
  disable path and clears the overlay. Revisit if Levi review demands otherwise.
- Overlay color assumes ARGB white; font uses preloader default (no bundled TTF).
