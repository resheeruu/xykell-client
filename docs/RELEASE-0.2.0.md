# Release 0.2.0

Tagged build. Everything below is a **host-verified** claim; nothing in this
release has been verified on a device, and the registry says so too.

## What this release contains

Nine ids delivered since the last tag, plus the change that makes HUD work
visible at all:

- **HUD overlay window.** A `TYPE_APPLICATION_OVERLAY` canvas painting the same
  tested C++ renderer the game-side overlay uses, at 4 Hz. Before this, no HUD
  value had ever been drawn in a session.
- **Relay → HUD observation hand-off**, which had been missing entirely: decoded
  relay output now reaches the observation consumer instead of going nowhere.
- `combat.backtrack`, `combat.afk_clicker`, `combat.double_click`,
  `misc.quick_drop`, `hud.tab_list`, `hud.server_info`, `hud.ip_display`,
  `player.death_position`, `player.spam`.
- New capabilities behind them: injected monotonic clock, bounded per-entity
  position history, `MacroStep.holdMs`.
- `player.spam` is the first **authored** (not rewritten) packet, with its scope
  documented and pinned by a round-trip test against the verified decoder.

Full detail: `docs/WHATS-NEW.md`.

## Verification actually performed

| Gate | Result |
|---|---|
| `scripts/test/run-kotlin-unit.sh` | 57/57 suites PASS (3 consecutive runs) |
| `scripts/test/run-unit.sh` | 37/37 suites PASS |
| `scripts/test/run-kotlin-typecheck.sh` | 124 main / 58 test sources |
| `scripts/test/check-i18n.py` | PASS, 7 locales × 536 keys |
| `scripts/registry/validate.py` | 258 features OK |
| `scripts/audit/full-feature-audit.py` | no fake `SUPPORTED` entries |
| `scripts/audit/stage4-relay-audit.py` | PASS, 12 scoped files |
| `scripts/test/jni_coverage.py` | PASS, 46 exports |
| `scripts/build-apk.sh` | PASS |

## Artifact

- Path: `app/build/outputs/apk/debug/app-debug.apk`
- Size: 3,967,401 bytes
- sha256 (first 32): `2eabc3a3810451bd22cd8dec8fec7848`
- Version: 0.2.0 (versionCode 2)
- Signing: **debug key only.** Release signing is not configured and no keystore
  exists in this repository; none will be committed.

## Known limitations — stated, not buried

- **Nothing is `SUPPORTED`.** That status requires a live server round-trip and
  no session has run. The registry reports 124 PARTIAL, 97 REFERENCE_ONLY,
  22 NOT_IMPLEMENTED, 15 DEVICE_LIMITED, 0 SUPPORTED.
- **The relay has never connected to a real server.** Everything is host-tested.
- **To see any HUD value:** install, grant "display over other apps", open the
  HUD screen, press Start, then run a session. MIUI blocks CLI installation, so
  this is a manual step.
- **About 119 ids remain**, gated on knowledge tables this project does not carry
  (block palette, item format, `Action` ordinals — see
  `docs/research/PACKET-TABLE-AVAILABILITY.md`), on in-process injection, or on
  server authority. They are not a backlog of effort.

## Upgrade notes

- `versionCode` 2. Installing over a previous build replaces it; no migration is
  needed because profile/HUD storage is unchanged.
- The overlay requires the `SYSTEM_ALERT_WINDOW` grant. Nothing starts without it
  and the app never assumes it.