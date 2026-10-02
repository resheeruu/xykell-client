# Crash guard & safe mode

`CrashGuard` (`crash_guard.{h,cpp}`), state file `<dataDir>/crashguard.json`
(dir from verified `ModContext::dataDir()`; no game hooks involved).

- `recordCrash(id, reason)`: counts; at 3 → auto-quarantine (persists reason).
  Quarantined modules stay disabled across restarts until `clearQuarantine()`.
- Safe mode: `enterSafeMode(reason)` / `exitSafeMode()` / `isSafeMode()`.
  Deterministic startup: safe flag set → core menu registers (recovery UI),
  HUD module skipped; `safeModeReport()` yields the exact launcher text:
  `SAFE MODE / Reason: <reason> / Disabled modules: <count>` (+ per-module lines).
- Corrupt state file → `load()` fails loudly; caller enters safe mode rather
  than guessing. `lastKnownGood` profile name tracked for recovery.
- Wired in `XykellMod::load/unload`: quarantine applied to the runtime
  ModuleManager; config persisted on unload. Tests: `test_crash`.
