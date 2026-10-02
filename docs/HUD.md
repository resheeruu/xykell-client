# HUD engine (spec; M1 proof only so far)

## Proven (M1)
Static overlay text via verified `submitDrawCommands` + tap counter via
verified `registerTouchCallback`. Placeholders shown honestly (`FPS: --`,
`coords: n/a`) until §Sources land.

## Engine (M2+)
Widget registry (one entry per `hud.*` in `registry/features.json`): movable,
scalable, toggleable, configurable, profile-aware; touch-first editor
(drag/resize); persistence in profile JSON.

## Sources (all RESEARCH_REQUIRED until verified)
Frame ticks (live FPS/CPS-timing), player position/rotation, inventory,
latency, world/biome data, chat, target info. No widget displays a value
without its source; unwired widgets stay hidden or show their status.
