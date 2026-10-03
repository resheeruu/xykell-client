# Stage-13 observation source (contract only, no live source)

## 1. Objective

Define the missing production source boundary — the item-level
read-only contract that can eventually feed the Stage-12 consumer —
without implementing, connecting, or pretending a Minecraft source.
Contract + composition proof only.

## 2. Stage 12 baseline

Idle `ObservationConsumer` → bounded `RuntimeObservationSnapshot`
(verified: semantics preserved, unknowns counted-not-promoted,
invalid unrepresentable, caller-isolated, constant size). Idle
because no production source exists — this stage defines that
boundary's shape.

## 3. Source contract

`native/include/xykell/runtime_observation_source.h`
(`xykell::runtime`, header-only): `PollingSource` —
`status()` + `pollNext() → SourcePollResult`, synchronous pull, one
item per call. Deliberate non-duplication: Stage 10's batch-pull
`ObservationSource` stays untouched as the bulk-drain form; this
header adds only what it cannot express (per-item
NoObservation/Observation/Unavailable). No callbacks, no transmit
methods, no control surface — pull exposes nothing to transmit.

## 4. Poll semantics

`SourcePollResult{outcome, observation}` with factories
`none()/unavailable()/item(...)`; `observation` meaningful ONLY when
`outcome == Observation`. Three states stay distinct: empty ≠ broken
≠ data. Errors are states, never synthesized observations.

## 5. Null source

`NullSource`: `status() → Unavailable`, `pollNext() → Unavailable`,
always, deterministically, offline. Allocates nothing, no I/O, no
network/clock/randomness/Minecraft logic. Safe empty source.

## 6. Synthetic source

Test-only `SyntheticSource` (lives in the test file, not production):
fixed fixture vector + index, emits in order, then `NoObservation`
forever; `status() → Ready`. No WebSocket/JSON/Minecraft/crypto/
network/captures; no growth (fixture fixed, `sizeof ≤ 128`
asserted); no threads/mutexes.

## 7. Observation ownership

`PollingSource → RuntimeObservation → ObservationConsumer`, never
circular: the source never owns/sees/calls a consumer (no back-pointer,
no callback); the consumer value-copies (Stage 12). Composition test
drains source→consumer in a loop and asserts both ends.

## 8. No raw envelope boundary

The contract accepts/emits only `RuntimeObservation`. JSON, strings,
bytes, ciphertext, packets, and frames cannot type-check here —
protocol parsing stays in lab/translator by construction.

## 9. Error semantics

`Unavailable` (null/broken source) vs `NoObservation` (healthy but
empty) vs `Observation` (data) are distinct outcomes; the composition
test feeds only `Observation` results to the consumer and asserts an
`Unavailable` result creates nothing (consumer stays at zero).

## 10. Memory semantics

Bounded both ends: source holds only its fixed fixture (no queue —
preferred "no queue at all" option taken); consumer holds the
constant-size snapshot (Stage 12). Exhausted/empty sources repeat
without allocating; 500× empty polls change nothing.

## 11. Threading semantics

None: synchronous deterministic calls, no threads, no async, no
mutexes. The codebase needs no other model for this boundary; had it,
it would have been documented before implementing — it was not needed.

## 12. RuntimeProvider status

Not integrated — deliberately deferred. `Runtime` owns a provider +
session manager with no observation input seam; threading one through
(member + forwarding + lifecycle) would be churn serving zero live
source. Stops at the contract + composition, per the task's explicit
prefer-defer rule. No provider/session/GameState file touched.

## 13. JNI status

Unchanged (bridge untouched): nothing exposed to Android UI — no
message/travel/status surface. No compile contract needed adjustment.

## 14. Capability registry status

Unchanged (no regeneration): a source contract is infrastructure, not
a capability — advertising live observation with no source would
over-claim.

## 15. Security audit

Control grep over new files hits only documentation negations ("no
network/crypto … surface", "Allocates nothing…") and the `sender`
field/fixture words — terminology, not capability. Holds: NO NETWORK,
NO CRYPTO, NO CONTROL, NO CREDENTIALS, NO PACKET SURFACE (two std
headers + model headers, no I/O, single `pollNext` entry). Lab,
Stage-10/11/12 files zero-diff; provider/JNI/UI/registry untouched.

## 16. Tests

- New `tests/unit/test_observation_source.cpp` (synthetic fixtures,
  never Minecraft data): null deterministic-offline; empty source
  repeat-NoObservation; message/travel exact-semantics composition;
  A/B/A/B ordering (ends on B, counts all four); unknown
  counted-not-promoted; unavailable-creates-nothing; fixture
  unmutated by drain; exhausted-source determinism + `sizeof` bound.
  PASS standalone.
- Wiring: +1 `run_case`, count 26→27.
- Full host suite: **27/27 PASS**; lab suite **51/51 PASS**
  (preflight; zero lab changes this stage). Nothing weakened/deleted;
  crypto tests untouched. Full NDK build not run on Termux (existing
  SDK/network constraint — reported, not claimed).

## 17. Proven

Item-level source contract compiles with three-state error
separation; null/empty/synthetic behaviors deterministic; source
order preserved end-to-end into the consumer snapshot with exact
field semantics; unknowns still never promoted; unavailable never
fabricates; memory bounded both ends; all suites green with every
boundary intact.

## 18. Not proven

A live production source (none exists — by design); U1 content;
anything beyond the two event types; any provider/JNI/UI consumption
(nothing wired — correctly).

## 19. Unknown

Carryovers only: U1 class, clock-exact attribution, ack semantics,
game-side verbatim (unchanged, untouched by this stage).

## 20. Next step

Contract waits, correctly unimplemented at the transport end. The
only honest next source work is a verified read-only producer
emitting translator-grade validated observations through
`pollNext()` — until one exists, no provider/JNI/UI/registry/capability
work belongs. Any future producer must preserve the three-state
semantics and re-run all suites.

No real Minecraft observation source exists in production. The source
contract is only an architectural boundary.
