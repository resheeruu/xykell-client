# Reference coverage (generated 2026-10-02 from `registry/features.json`)

Coverage = the capability exists in Xykell's design/registry with honest
status — NOT a claim it works. Counts overlap (entries cite multiple sources).

| Source | PARTIAL | RR | NI | Notes |
|---|---|---|---|---|
| Lunar Proxy | 0 | 128 | 0 | all 101 catalog items mapped + extras (see below) |
| Flarial | 0 | 60 | 2 | 52 Android + key Windows items mapped |
| Atlas | 0 | 27 | 0 | QoL universe mapped |
| Apollon | 0 | 19 | 0 | secondary sources only (APOLLON_SOURCE_UNVERIFIED) |
| BedrockTools | 0 | 16 | 0 | shape reference; GPL boundary, no code |
| WClient | 1 | 6 | 0 | config inspiration (PARTIAL = our own store) |
| Nova | 0 | 4 | 0 | packet-pattern inspiration only |
| LeviLaunchroid | 0 | 0 | 3 | launcher primitives referenced, not duplicated |
| Xykell (original) | 8 | 17 | 10 | infra, themes, diagnostics, local systems |

## Lunar 101 checklist (authoritative catalog fetched 2026-10-02)
- Combat 25/25 mapped (incl. DoubleClick, AutoLog). All RR.
- Movement 16/16 mapped. All RR.
- Visual 17/17 mapped + spawner_ping extra. All RR.
- Player 13/13 mapped + mod_alerts note corrected. All RR.
- World 8/8 mapped. All RR.
- Toggles 16/16 mapped (ShowCoords/CPS → hud equivalents). All RR.
- Misc 6/6 mapped. RR/NI.
- Ghost meaning corrected (chat-hiding, NOT phase-through).

## Flarial checklist (authoritative /modules fetched 2026-10-02)
- Android 52: Movement 6/6, HUD 20/20 (incl. new entity_counter…totem_counter),
  Render 16/16 mapped, Utility 10/10 (incl. auto_gg…player_notifier). All RR
  except hud.hardware_stats (RR — OS-API path documented, not yet implemented).
- Windows-only items mapped where mobile-meaningful (FOV changer → zoom notes,
  perspective → movement.perspective, view bobbing → notes).

## Method
`python3 scripts/registry/generate.py` → `validate.py` → `generate_catalog.py`
→ counts above. Re-run after any registry change; CI enforces schema +
fake-SUPPORTED gate (per-source breakdown is report-time).
