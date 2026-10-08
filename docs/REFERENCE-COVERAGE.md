# Reference coverage (generated 2026-10-07 from `registry/features.json`)

Coverage = the capability exists in Xykell's design/registry with honest
status — NOT a claim it works. Counts overlap (entries cite multiple sources).

| Source | PARTIAL | RR | NI | DL | Notes |
|---|---|---|---|---|---|
| Lunar Proxy | 3 | 119 | 6 | 0 | 101 catalog items mapped + extras (see below) |
| Flarial | 14 | 20 | 24 | 4 | 52 Android + key Windows items mapped |
| Atlas | 5 | 11 | 1 | 11 | QoL universe mapped |
| Apollon | 1 | 17 | 1 | 0 | secondary sources only (APOLLON_SOURCE_UNVERIFIED) |
| BedrockTools | 4 | 7 | 3 | 2 | shape reference; GPL boundary, no code |
| WClient | 4 | 3 | 0 | 0 | config inspiration (PARTIAL = our own store) |
| Nova | 2 | 2 | 0 | 0 | packet-pattern inspiration only |
| LeviLaunchroid | 3 | 0 | 0 | 0 | launcher primitives referenced, not duplicated |
| Xykell (original) | 30 | 2 | 3 | 2 | infra, themes, diagnostics, local systems |

RR = REFERENCE_ONLY (mapped, policy or source blocked), NI = NOT_IMPLEMENTED,
DL = DEVICE_LIMITED. Counts from per-entry `sourceReferences`.

## Lunar 101 checklist (authoritative catalog fetched 2026-10-02)
- Combat 25/25 mapped (incl. DoubleClick, AutoLog). PARTIAL:
  `combat.auto_clicker` (Accessibility dispatchGesture taps) and
  `combat.velocity` (drops clientbound SetEntityMotion 0x28). Rest RR.
- Movement 16/16 mapped. PARTIAL: `movement.levitate` and
  `movement.movement_correction` (both outbound/inbound MovePlayer 0x13
  rewrites). Rest RR.
- Visual 17/17 mapped + spawner_ping extra. PARTIAL: `visual.fullbright` and
  `visual.time_changer` (both rewrite SetTime 0x0A). Rest RR.
- Player 13/13 mapped + mod_alerts note corrected. All RR except
  `hud.arraylist` + `misc.timer` PARTIAL (sourced here too).
- World 8/8 mapped. All RR.
- Toggles 16/16 mapped (ShowCoords/CPS → hud equivalents). All RR.
- Misc 6/6 mapped. RR/NI (shulker_tooltip, death_lightning = NI).
- Ghost meaning corrected (chat-hiding, NOT phase-through). `automation.ghost`
  is now PARTIAL: it drops serverbound Text 0x09 chat/whisper only.
- Every remaining RR id carries a per-id reason in `registry/features.json`
  (evidence `assessed: not deliverable by a packet relay`), parsed from the
  `IMPOSSIBLE` maps in `app/src/main/java/dev/xykell/client/runtime/modules/`.
  RR here means the capability is not deliverable by a packet relay: it needs
  input injection, a render/overlay pass, inventory or world state this app
  does not decode, or state across packets. Counts above are status counts;
  per-id truth is the registry.

## Flarial checklist (authoritative /modules fetched 2026-10-02)
- Android 52: Movement 6/6, HUD 20/20 (incl. new entity_counter…totem_counter),
  Render 16/16 mapped, Utility 10/10 (incl. auto_gg…player_notifier).
- Status: 14 PARTIAL (FPS/CPS/coords/clock/watermark/notifications/
  session_stats/hardware_stats/stop_watch/movable_hud, waypoints,
  world_markers, scripting runtime+api), 24 NI (game-sourced HUD values and
  automation — Stage-20 observation source absent), 4 DEVICE_LIMITED, 20 RR.
- Windows-only items mapped where mobile-meaningful (FOV changer → zoom notes,
  perspective → movement.perspective, view bobbing → notes).

## Method
`python3 scripts/registry/generate.py` → `validate.py` → `generate_catalog.py`
→ counts above. Re-run after any registry change; CI enforces schema +
fake-SUPPORTED gate (per-source breakdown is report-time).
