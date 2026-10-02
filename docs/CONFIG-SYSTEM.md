# Config system

`XykellConfig` (`native/.../config_store.{h,cpp}`) over hand-rolled strict JSON
(`json_min`, no third-party dep).

- File: `<configDir>/xykell.json` (dir from verified `ModContext::configDir()` at
  runtime; temp dirs in tests). Schema version `1`, sections:
  client/modules/hud/gui/rendering/input/network/profile.
- Load: missing → clean defaults (usable). Unparseable/bad schema →
  `<file>.corrupt.<timestamp>` backup + defaults; `recovered()==true`.
  Malformed values → typed safe fallbacks, never a crash.
- Save: atomic (tmp + rename); creates parent dir (preloader convention is
  that mods own their config dir).
- Migration: `migrate(from)` fills new keys from defaults, stamps
  `schemaVersion`, reports `migratedFrom()`.
- Rules: no credentials/tokens (nothing in the schema holds secrets);
  no telemetry. Tests: `test_config` (round-trip, corrupt, malformed).
