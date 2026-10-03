# Stage-10 event model (read-only runtime mapping)

## 1. Objective

Convert the two Stage-9P-verified Minecraft events (PlayerMessage,
PlayerTravelled) into Xykell-owned read-only production types without
connecting production to live Minecraft. Architecture/mapping only —
no gameplay control, no networking, no crypto work.

## 2. Stage 9 evidence

9P (Minecraft 1.26.52.3, frozen crypto, 38/38 lab tests): 2/2 gated
sessions, exactly-once establishment + subscribes, 53/53 expected
envelopes decrypted (3 chats with verbatim user text `hiiii`/`hello`/
`working?`; 50 travel frames, meters ≈1.0–1.35, ~150–500 ms cadence);
recurring U1 (404–405 wire, first post-session frame, non-JSON) and U0
(115 wire, pre-session) unknown classes. Full report:
`docs/research/STAGE-9P-EVENT-COVERAGE.md`.

## 3. Architectural decision

One new header-only model, `native/include/xykell/
runtime_event_observation.h` (`xykell::runtime`), extending the
existing `runtime_provider.h` "immutable observation snapshot"
vocabulary (plain data, no mutators, `*AtMs` millis). Rationale:
production already speaks observations (`ConnectionObservation`,
`PlayerObservation`, …); the new types join that language instead of
forking it. Header-only = one file + one test + one `run-unit.sh`
line; no build redesign, no JNI/Kotlin/registry/native changes. The
model accepts already-parsed primitives only, so transport
dependence (WebSocket/JSON/crypto/captures) is unrepresentable.
Conceptual flow preserved: Minecraft → lab → verified source event →
`RuntimeObservation` → `RuntimeProvider` boundary → future consumers
(the last two arrows are contracts only; no live source exists).

## 4. PlayerMessage model

`PlayerMessageObservation { eventId, observedAtMs, sender, message }`
— exactly the §3 candidate fields. Factories reject empty id/sender/
message. `receiver` omitted (always `""` in evidence — unproven as
signal, not defaulted into meaning).

## 5. PlayerTravelled model

`PlayerTravelObservation { eventId, observedAtMs, position{x,y,z},
yawDegrees, metersTravelled, travelMethod }`. Rotation is yaw-only
(only `yRot` ever observed; pitch omitted, not zero-filled).
`travelMethod` is the raw observed int (0 and 2 seen) with explicitly
unverified semantics — preserved opaquely, never mapped. Omitted as
unproven: velocity/health/inventory/dimension/entity id/tick/
acceleration/collision/authoritative world state.

## 6. Proven fields

sender, message, position xyz, yaw, metersTravelled, travelMethod
(raw int), event arrival itself — each demonstrated across 9N/9P
sessions and named in verified envelopes. `eventId`/`observedAtMs`
are Xykell-side bookkeeping (deterministic caller-supplied ids).

## 7. Unproven fields

Everything in §4/§5 "omitted" lists, plus: receiver semantics, pitch,
travelMethod meanings, subscription acks, clock-exact action timing,
any event type beyond the two, any world/entity state. Where the model
needs "not present" it omits the field; `UnknownObservation` covers
the lab's U1/U0 as metadata only.

## 8. Timestamp semantics

`observedAtMs` = time Xykell observed the event (observer clock,
matching the `atMs` provider-clock convention). It is NOT Minecraft
action time — 9P never clock-proved causality. Documented in-header.
No synchronized-clock assumptions; no clock abstraction introduced
(none exists in the codebase beyond provider-clock millis).

## 9. Unknown-frame policy

U1/U0 stay inside the lab (Stage-9O/9P reports). Production gains only
`UnknownObservation { eventId, observedAtMs, wireLength, reason }` —
no payload, ciphertext, plaintext, or inferred meaning. No U1 parser,
no classification, no parser to be added later without new evidence.

## 10. Observation-source boundary

`ObservationSource` (pure `poll() → vector<RuntimeObservation>`) feeds
the `RuntimeProvider` side; `NullObservationSource` (always empty)
proves the boundary compiles silent. Read-only by construction: the
interface has no send/execute/inject/write/auth surface and none may
be added there (§13 audit confirms).

## 11. Security boundary

Observation-only end to end: model (data validation only), boundary
(no control methods), tests (synthetic fixtures), docs (no secret
handling described). Diff audit (§13 of task): the only audit hits are
documentation words ("Do not rename these to *State";
"no send/execute/inject/write") — terminology, not capability. No
networking permission, WebSocket dependency, JNI field, or credential
path added anywhere.

## 12. Production integration status

A live Minecraft production observation connection is NOT
implemented. Unchanged and why: JNI/`bridge.cpp` (nothing to expose —
no live source); Kotlin `RuntimeManager` (stub `NOT_WIRED`; nothing
to wire); `native/CMakeLists.txt` (header-only: no new TU, verified
by coexistence compile); `registry/features.json` (generated; adding
`player_message_observation`/`player_travel_observation` entries with
no live production path would over-claim — left unchanged
deliberately); lab behavior and crypto tests (untouched, 38/38 still
green).

## 13. Tests

- New `tests/unit/test_runtime_observation.cpp` (synthetic fixtures,
  no raw captures): message valid/sender/message/timestamp preserved;
  travel valid/position/yaw/meters/method/timestamp preserved +
  second travelMethod value unmapped; invalid (empty id/sender/
  message/reason, NaN/Inf position/yaw/meters, negative meters);
  unknown metadata-only; boundary abstract + null silent.
- `scripts/test/run-unit.sh`: +1 `run_case`, count 24→25.
- Results: new test PASS standalone; full host suite **25/25 PASS**;
  lab suite **38/38 PASS** (unchanged behavior); coexistence TU
  (observation + session + provider + event_bus headers) compiles
  `-Wall -Wextra -Werror` clean. No existing test weakened/deleted;
  crypto/lab tests untouched.

## 14. Proven

The two observed event shapes map losslessly onto the model (all
verified 9P fields representable; all unproven fields omitted);
validation rejects every malformed-fixture class; the source boundary
is abstract and control-free; the full suites stay green.

## 15. Not proven

Live production observation (no connection, by design); U1 content;
clock-exact attribution; ack semantics; any runtime consumption of
these types (no consumers wired — correctly so).

## 16. Next step

Minimal: keep types consumer-free until a read-only source exists. If
a future stage builds a lab-to-runtime translator, it must construct
these types from verified envelopes only (per-field provenance), add
no fields without new evidence, and re-run both suites. No adapter,
no JNI, no registry entry before that translator is itself verified.

The event model is implemented. A live Minecraft production
observation connection is NOT implemented.
