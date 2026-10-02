# Xykell Client

Original, modular **Minecraft Bedrock (MCPE)** client — **Android ARM64 first, touch first**. QoL/perf/HUD/visual core; combat/movement/world automation only as opt-in Advanced/Experimental, OFF by default.

Direction: standalone `Xykell Launcher → Xykell Runtime → Minecraft Bedrock`
(zero Levi/Preloader in final production). Current code runs on the Levi
preloader path behind Xykell-owned `portal/` seams labeled LEGACY
COMPATIBILITY MODE while the standalone loader is researched
(see `docs/STANDALONE-MIGRATION-AUDIT.md`).

Not affiliated with Mojang/Microsoft. Requires a legitimate Play copy of Minecraft. No credential handling, no hidden telemetry, no bypasses (see docs/LEGAL-LICENSE.md).

## Status
Architecture + honest registry (255 entries, zero fake SUPPORTED) + CI green.
Runtime/device verification pending — see `docs/DEVICE-TESTING.md`.
Key docs: ARCHITECTURE, RUNTIME-CAPABILITIES, FEATURE-MATRIX (pointer),
MODULE-CATALOG (generated), LEVI-REMOVAL-AUDIT.
