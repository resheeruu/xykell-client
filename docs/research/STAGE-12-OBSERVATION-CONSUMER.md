# Stage-12 observation consumer (read-only runtime boundary)

## 1. Objective

Prove the runtime layer can safely consume Stage-10 observations
(PlayerMessage, PlayerTravelled, UnknownObservation) into a bounded
read-only snapshot — without a live source, networking, crypto, or
control. Consumer boundary only; nothing consumes it yet.

## 2. Stage 11 baseline

Translator (`lab/bedrock-websocket/translate.mjs`, untouched this
stage): 54 real post-establishment events mapped with zero field
loss, 3 U1 → Unknown, 0 Invalid on captures; deterministic frozen
outputs; Unknown ≠ Invalid. Production models exist with no
consumer — this stage adds exactly that boundary.

## 3. Consumer architecture

`native/include/xykell/runtime_observation_consumer.h`
(`xykell::runtime`, header-only): single-method `ObservationConsumer::
consume(const RuntimeObservation&)` dispatching the variant into a
`RuntimeObservationSnapshot`. Chosen over per-type `onX()` overloads
as the smallest seam fitting the codebase (one entry point, mirrors
`EventBus::publish(const Event&)`). No other abstraction added.

## 4. Runtime snapshot

`RuntimeObservationSnapshot`: `latestMessage`,
`latestTravel` (both `optional` — absence is explicit, never
fabricated), `messageCount`/`travelCount`/`unknownCount`,
`lastObservedAtMs`, `lastUnknownWireLength`, `lastUnknownReason`.
Only fields useful to the current architecture; no history, bus, or
stats system. Named snapshot, never *State — Observed ≠
authoritative is documented in-header.

## 5. Initial state

Deterministic: optionals empty, all counts 0, timestamps 0. No
`position = 0,0,0` / `sender = ""` presented as observation — the
test asserts emptiness, not placeholder values.

## 6. PlayerMessage handling

Value-copied into `latestMessage` (replaces previous), `messageCount`
+1, `lastObservedAtMs` updated. Sender/message/timestamp preserved
exactly (asserted).

## 7. PlayerTravelled handling

Value-copied into `latestTravel` (replaces previous): position xyz,
yaw, meters, raw travelMethod preserved; `travelCount` +1, timestamp
updated. No semantic mapping of `travelMethod` (still raw int).

## 8. Unknown handling

Counted (`unknownCount` + metadata wire/reason) and otherwise
dropped: creates no message/travel state, retains no payload
(there is none — Unknown carries metadata only). U1 is never parsed,
promoted, or reclassified here.

## 9. Invalid-input boundary

Unreachable by construction: the consumer accepts only
`RuntimeObservation` values, which can only be built through the
Stage-10 validating factories. Raw JSON, envelopes, ciphertext, and
protocol payloads cannot type-check at this boundary — they remain
lab/translator responsibilities. Documented in-header.

## 10. Immutability

Stored state is a value copy (`*m`/`*t` dereferenced into the
snapshot; strings/vectors deep-copied by value semantics), so
caller-side mutation after `consume()` cannot alter it (asserted:
mutating the source post-consume leaves the snapshot intact).
Snapshot access is const-ref only (asserted via `static_assert` on the
return type). No setters exist.

## 11. Memory bound

Constant shape: two optionals + five `uint64_t` + one short string
(`static_assert`-adjacent runtime check: `sizeof < 512`). 3000-observation
soak test changes counts only. No list/database/file/network —
phone-first holds.

## 12. RuntimeProvider integration

Not integrated — deliberately. The `Runtime` class owns a provider
and session manager with no observation input seam; threading one
through (member + forwarding + lifecycle questions) would be
architectural churn serving zero live source. The consumer stands
alone behind its documented boundary until a verified source exists.
No `RuntimeProvider`, session, or GameState file touched.

## 13. JNI status

Unchanged (`bridge.cpp` untouched): no methods added, no
observations exposed to Android UI, no network/control surface. No
pre-existing compile contract required adjustment.

## 14. Capability registry status

Unchanged (`features.json` untouched, no regeneration): advertising
live observation capabilities with no production source would
over-claim — same standing reason as Stage 10.

## 15. Security audit

Control-surface grep over new files hits only documentation
negations ("no network/crypto … surface") and test field names
(`sender`) — terminology, not capability. Consumer holds: NO
NETWORK, NO CRYPTO, NO CONTROL, NO CREDENTIALS, NO PACKET SURFACE
(by construction: two std headers + model header, no I/O, no
sockets, single `consume` entry). Lab (§4 of task list) untouched
this stage; Stage-10/11 files zero-diff.

## 16. Tests

- New `tests/unit/test_observation_consumer.cpp` (synthetic
  Stage-10-schema fixtures, never Minecraft data): initial emptiness;
  message stored/preserved/replaced/counted; travel full-telemetry
  preserved (incl. raw method) and replaced; multi-type sequence
  latest-correct; unknown counted-not-promoted with metadata;
  caller-mutation isolation + const-access; 1000×3 soak with
  `sizeof < 512`. PASS standalone.
- Wiring: +1 `run_case` in `run-unit.sh`, count 25→26.
- Full host suite: **26/26 PASS**; lab suite **51/51 PASS**
  (no Stage-9/11 behavior change). No test weakened/deleted; crypto
  tests untouched. Full NDK build not run on Termux (existing
  SDK/network constraint — reported, not claimed).

## 17. Proven

Xykell-owned observations flow into a safe runtime consumer with
semantics preserved (fields, timestamps, latest-wins, counts);
unknowns counted without promotion; invalid input unrepresentable;
state constant-size and caller-isolated; all suites green with
lab/production boundaries intact.

## 18. Not proven

Live production observation (no source — by design); U1 content;
anything beyond the two event types; any consumption of the snapshot
(no readers wired — correctly so).

## 19. Next step

Consumer waits, correctly idle. The only honest next integration is a
verified read-only source feeding `consume()` — until one exists, no
provider/JNI/UI/registry work belongs. Any future source must emit
only translator-grade validated observations and re-run all suites.

The consumer accepts Xykell-owned observations. No production
Minecraft observation source exists. No live WebSocket connection
exists.
