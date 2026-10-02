# Xykell Client

Original, modular **Minecraft Bedrock (MCPE)** client — **Android ARM64 first, touch first**. QoL/perf/HUD/visual core; combat/movement/world automation only as opt-in Advanced/Experimental, OFF by default.

Not affiliated with Mojang/Microsoft. Requires a legitimate Play copy of Minecraft. No credential handling, no hidden telemetry, no bypasses (see docs/LEGAL-LICENSE.md).

## Status
Phase 0/1 — research + repo skeleton. Nothing playable yet. See docs/RESEARCH.md, docs/FEATURE-MATRIX.md, docs/ARCHITECTURE.md.

## Layout
`client/` core+abstractions · `modules/` categories · `launcher/android` · `assets/` · `tests/` · `scripts/` · `docs/`.

## Next
Milestone 1 (Phase 24): core (registry/events/config/profiles) → touch UI + ClickGUI → FPS/CPS/coords/keystrokes → PerformanceManager → version detection → crash handling → first Android test build.
