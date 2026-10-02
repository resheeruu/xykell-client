# Runtime substrate (Stage 4)

Stateless-capable provider layer between Xykell Runtime and capability
consumers. Design source: `reference-analysis/normalized/xykell-substrate-design.md`
and Stage-3B relay findings (in the analysis workspace, not this repo).

## What exists

- `native/include/xykell/runtime_provider.h` / `native/src/xykell_runtime_provider.cpp`
  - `Runtime` (substrate): owns one provider, enforces lifecycle
    STOPPED → STARTING → RUNNING (→ STOPPING → STOPPED), FAILED on provider
    failure. Idempotent stop, no duplicate starts, no hot-swap while running,
    unknown provider names rejected, errors observable via `diagnostics()`.
  - `RuntimeProvider` (interface): `start/stop/state/capabilities/diagnostics`
    plus nullable `CapabilitySink`. No transmit/mutate API exists by design.
  - `SyntheticRelayProvider`: deterministic in-memory lifecycle model
    (connect → session → fixed synthetic observations → disconnect), two
    synthetic endpoints with TTL/discovery, no sockets, no accounts.
  - `NativeProviderStub`: honest `UNAVAILABLE` failure (never fake success).
  - Read-only snapshots: connection/session/player/entity/world/chat +
    diagnostics. Immutable plain data; sink receives const references.
  - EventBus reuse: lifecycle is observable through provider `state()` +
    `diagnostics()`; the bus carries payload-less events, so capability data
    travels via `CapabilitySink` instead of a parallel bus (deliberate).
- `tests/unit/test_runtime_provider.cpp` (22nd suite in
  `scripts/test/run-unit.sh`): lifecycle, failure/recovery, endpoints
  (coexist/select/disappear/reappear/TTL), sessions, sink consumption,
  no-emission-after-shutdown, config-key contract.
- `scripts/audit/stage4-relay-audit.py`: HARD structural gate (forbidden
  gameplay-action APIs + socket APIs in scoped files; negative-controlled).

## Status declarations (do not overclaim)

- Synthetic Relay ≠ Minecraft protocol implementation.
- Synthetic Session ≠ real Minecraft session.
- Native Provider = NOT IMPLEMENTED (lab-gated stub reports UNAVAILABLE).
- Real Protocol Mapping = LAB-GATED (no authorized environment exists).
- Launcher UI wiring (status display) and JNI bridge for provider state are
  Stage-5 work: the phone cannot compile Kotlin/Gradle here, so no
  unverifiable UI code was added. `xykellcore` launcher lib is untouched.

## Config

`Runtime::kConfigSection/kConfigKeyProvider` (`runtime/provider`,
default `synthetic-relay`) integrate with `XykellConfig` get/setString;
persistence is caller-owned. No credentials are ever stored (none exist).
