# Stage-11 lab translator (read-only representation, no integration)

## 1. Objective

Prove the verified Stage-9 event envelopes can be represented by the
Stage-10 Xykell-owned observation types through one lab-only,
read-only translator — representation, not integration. No live
production connection, no gameplay control, no crypto work.

## 2. Stage 10 baseline

`native/include/xykell/runtime_event_observation.h` (unchanged this
stage — verified zero diff): `PlayerMessageObservation
{eventId, observedAtMs, sender, message}`, `PlayerTravelObservation
{eventId, observedAtMs, position, yawDegrees, metersTravelled,
travelMethod(raw int)}`, `UnknownObservation
{eventId, observedAtMs, wireLength, reason}`, `RuntimeObservation`
variant, read-only `ObservationSource` + `NullObservationSource`.
`observedAtMs` = observation time, never action time.

## 3. Input contract

`translateEnvelope(value, ctx)` takes an already-decrypted,
already-parsed envelope object (the output of the lab's
`decodeInbound`), or a JSON string (parsed here; unparsable →
`Invalid`). Ciphertext/Buffers/binary never qualify. The translator
owns no connection, encryption, decryption, key exchange,
subscription, or I/O — verified envelope → normalized observation
only. Context (caller-supplied, deterministic):
`{eventId, observedAtMs, wireLength}`.

## 4. Translation boundary

`lab/bedrock-websocket/translate.mjs` (+ `test/translate.test.mjs`,
+ one `package.json` test-script entry). Lab-scoped; nothing moved
toward `app/src/`, `native/`, JNI, or `RuntimeProvider`. Result
vocabulary: `{kind: PlayerMessage|PlayerTravelled, observation}` |
`{kind: Unknown, observation}` | `{kind: Invalid}` (no observation).
Unknown ≠ Invalid: Unknown = well-formed receipt, unproven meaning;
Invalid = malformed/unusable. Outputs `Object.freeze()`-d.

## 5. PlayerMessage mapping

`eventName === 'PlayerMessage'` + non-empty string `body.sender` +
non-empty string `body.message` → observation with all four Stage-10
fields; message preserved byte-for-byte (test asserts surrounding
whitespace/case/punctuation intact); `receiver` absent per the model.
Anything else in that shape → `Invalid`. Real texts (`hiiii`,
`hello`, `working?`) all translate (validation §11).

## 6. PlayerTravelled mapping

`eventName === 'PlayerTravelled'` + `body.player` record + finite
`position{x,y,z}` + finite `player.yRot` (yaw-only; pitch never
observed) + finite non-negative `metersTravelled` + finite-number
`travelMethod` (raw passthrough, observed 0/2 unmapped) → observation
with exact numerics (no rounding; `deepEqual` cross-checked §11).
Unobserved telemetry (pitch/velocity/health/inventory/dimension/
entity id/tick/collision) never added — not present in output by
construction.

## 7. Unknown handling

Well-formed event envelope with unrecognized `eventName` →
`Unknown` (`reason: unrecognized-event`); well-formed non-event
envelope (`commandResponse`/subscribe/…) → `Unknown`
(`non-event-envelope`); unparsed capture records → `translateUnparsed`
→ `Unknown` (`unparsed-input`, wire metadata only). U1 is therefore
representable as Unknown without ever parsing or storing its content;
U0 stays outside the translator (pre-session by definition — the
validation harness only feeds post-subscribe inbound records).

## 8. Invalid handling

Non-objects, unparsable strings, missing `header`, missing/empty
`sender`/`message`, wrong field types, NaN/±Infinity coordinates/yaw/
meters, negative meters, bad context (empty id, null ctx) → `Invalid`
with no observation object. Covered per-class by tests (§12 of task).

## 9. Event ID policy

Caller-supplied deterministic ids (Stage-10 rule): no random UUIDs,
no secret/account derivation. Validation harness rule:
`xykell-obs-<captureEpochMs>-<recordIndex>` (same record → same id);
unit fixtures use fixed ids; determinism test asserts identical
input twice → `deepEqual` output.

## 10. Timestamp policy

`observedAtMs` = when the lab observed/processed the event (capture
record time in validation). Never action time; no causality
inferred; no synchronized-clock assumptions.

## 11. Real capture validation

Read-only run over both 9P captures (unmodified), post-subscribe
inbound records only (handshake/control excluded — not gameplay
observations), cross-checking every Stage-10 field against the source
envelope:

- A (`...08-35-07-457Z`, 26 inbound): PlayerMessage 1,
  PlayerTravelled 24, Unknown 1, Invalid 0; field-mismatches 0.
- B (`...08-36-29-002Z`, 28 inbound): PlayerMessage 1,
  PlayerTravelled 26, Unknown 1, Invalid 0; field-mismatches 0.
- Coverage agrees with 9P up to a transparent correction: 9P §5/§6
  undercounted B travel as 25 (its own frame total 28 implies 26);
  authoritative counts are 24 (A) + 26 (B) + 1 (9N) = 51 cumulative
  travel, 3 chat, 3 U1-Unknown. No field silently lost, none added.

## 12. Test results

- New `test/translate.test.mjs`: **13/13 PASS** (message exactness +
  determinism + frozen-output mutation throw; travel numerics +
  frozen position + raw method values; unknown/unrecognized/
  non-event/unparsed; invalid JSON/missing-fields/wrong-types/
  NaN-Inf-negative/malformed-envelope/bad-ctx; JSON-string input).
- Lab suite: **51/51 PASS** (38 existing + 13 new; `package.json`
  test script extended by one path — the only non-test lab edit).
- Host suite: **25/25 PASS** (Stage-10 intact). No test weakened or
  deleted; crypto/lab behavior unchanged.

## 13. Security boundary

Capability grep over translator + tests hits only field names
(`sender`), fixture text, and documentation words (`commandResponse`
envelope kind, "NO command path") — terminology, not capability.
Translator has NO NETWORK / SOCKET / WEBSOCKET CLIENT / CRYPTO /
COMMAND PATH / CREDENTIAL PATH (verified by construction: pure
functions over plain objects, zero imports beyond `node:test`/
`node:assert` in tests). No secrets handled (envelopes contain only
game-event content from the user's own test world).

## 14. Production boundary

`runtime_event_observation.h` and its test: zero diff (verified).
`app/src/`, `native/src/`, JNI/`bridge.cpp`, `RuntimeProvider`:
untouched, no live WebSocket integration. Android manifest/
permissions: untouched. Dependencies: none added (lab `package.json`
diff is the test-script line only). Registry: untouched (no live
path — same reason as Stage 10).

## 15. Proven

Every verified 9P envelope maps onto the Stage-10 model with zero
field loss/gain (cross-checked programmatically); Unknown vs Invalid
semantics hold on real captures (3 U1 → Unknown, 0 Invalid);
determinism + frozen immutability hold; all three suites green;
boundaries (lab-only, read-only, no-control, no-production) intact.

## 16. Not proven

Live production observation (nothing consumes the objects — by
design); U1 content; clock-exact attribution; ack semantics; anything
beyond the two event types.

## 17. Next step

Representation is proven; integration is not started. If a future
stage wires a read-only source, it must emit only these translator
outputs from verified envelopes, add no fields without new evidence,
and re-run all suites. No adapter, JNI, registry, or permission work
belongs before that source is itself verified.

The translator is lab-only. No live production Minecraft connection
exists. No gameplay control exists.
