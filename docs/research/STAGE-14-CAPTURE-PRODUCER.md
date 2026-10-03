# Stage-14 capture producer (recorded-evidence bridge, no live source)

## 1. Objective

Prove the complete read-only data path — real capture → translator →
producer (`pollNext`) → consumer → snapshot — using recorded Stage-9P
evidence only. No socket, no Minecraft, no transport, no crypto, no
control. Representation chain, not integration.

## 2. Stage 13 baseline

Item-level `PollingSource` contract (`NoObservation`/`Observation`/
`Unavailable`), null/test sources, source→consumer composition proven,
all suites green (host 27/27, lab 51/51 preflight). The contract
waited transport-empty; this stage feeds it recorded evidence.

## 3. Capture inputs

Both 9P captures verified present on disk (git-ignored lab evidence,
read-only, unmodified):
`lab/bedrock-websocket/captures/capture-2026-10-03T08-35-07-457Z.jsonl`
(32 lines) and `.../capture-2026-10-03T08-36-29-002Z.jsonl` (34
lines). Had they been absent the stage would have stopped with no
fabricated replacements.

## 4. Capture A results

26 post-establishment inbound → PlayerMessage 1, PlayerTravelled 24,
Unknown 1, Invalid 0; `skippedMalformed` 0. Snapshot semantics: latest
(only) message `"hello"` verbatim; latest travel = final observed
travel frame; unknown count 1. Order: Unknown (U1) first, chat
mid-stream — capture order preserved exactly.

## 5. Capture B results

28 post-establishment inbound → PlayerMessage 1, PlayerTravelled 26,
Unknown 1, Invalid 0; `skippedMalformed` 0. Latest message
`"working?"` verbatim; latest travel = final observed frame.
(Authoritative 26, per the Stage-11 correction of 9P's 25 undercount.)

## 6. Producer design

`lab/bedrock-websocket/capture-producer.mjs`, class `CaptureProducer`:
constructor takes parsed record objects + `firstEventIndex` (caller-
computed: after the last non-inbound record, so handshake/keys/
subscribes/outbound/U0 stay transport evidence); index iterator over
the caller array (no copy, no history, no persistence, no queue);
`pollNext()` per the Stage-13 outcome shape; `kind()` always
`'capture-test-source'` (never Minecraft/live/connected);
`skippedMalformed`/`emitted` counters. `Unavailable` never produced
by file-backed evidence (reserved for live-source failures).

## 7. Translator reuse

No event-parsing duplication: parsed records go through the
unmodified Stage-11 `translateEnvelope` (same eventId/observedAtMs/
wireLength context rule → identical output, proven §12 of task flow);
unparsed records through `translateUnparsed` (metadata only).
Programmatic cross-check: producer output vs direct translator output
is JSON-identical for all 26 + 28 records.

## 8. Poll semantics

`{outcome:'Observation', observation, resultKind}` per item (the
`resultKind` label is lab-test counting metadata, not contract
surface); `{outcome:'NoObservation'}` at exhaustion, deterministically
forever (200× asserted); `Unavailable` never emitted here.

## 9. Event ordering

Capture order preserved: no reordering by type, no timestamp sort, no
merging. Verified: first post-establishment item is Unknown (U1) in
both sessions; id sequence re-drain reproduces identical eventIds.

## 10. Unknown handling

U1 → `UnknownObservation{eventId, observedAtMs, wireLength:404,
reason}` with exactly the five model keys (asserted key-set; frozen).
No payload examined/stored, no classification, no rename, no
promotion, no meaning inferred.

## 11. Invalid handling

Malformed records (null, non-object, bad timestamp, missing raw,
unparsable JSON, translator-Invalid) are SKIPPED with counted
`skippedMalformed` — never fabricated into Unknown or events
(poisoned-capture test: 5 malformed lines → skipped 5, real counts
1/24/1 intact). Deterministic and reported, not silent.

## 12. End-to-end composition

capture → translator → `producer.pollNext()` → consumer → snapshot:
snapshot ends with message 1 / travel 24+26 / unknown 1+1, latest
values verbatim/final, counts exact — the full read-only path proven
on real evidence with no live endpoint.

## 13. Memory behavior

Index + two counters; exhausted polling changes nothing (asserted
stall of `emitted+skipped+index` over 200 polls). Test-only bounded
use documented in-header; no database, no runtime queue.

## 14. Production boundary

Test/lab code only. Untouched: `runtime_event_observation.h`,
`runtime_observation_consumer.h`, `runtime_observation_source.h`,
`RuntimeProvider`, JNI, Android UI/manifest/permissions, registry,
Stage-9/10/11/12 behavior. Production source remains unimplemented
(correctly). Lab `package.json` test-script +1 path is the only
non-test edit.

## 15. Security audit

Capability grep over new files: only documentation negations ("NEVER
opens a socket…", "no connection state") — terminology, not
capability. Holds: Network NONE, Crypto NONE, Control NONE,
Credentials NONE (pure functions over record objects; single new
dependency-free module + test).

## 16. Tests

- New `test/capture-producer.test.mjs`: **7/7 PASS** (source-kind
  identity; A end-to-end 1/24/1 + snapshot + order + id
  determinism; B end-to-end 1/26/1; unknown key-set/frozen/404-wire;
  5-malformed skipped-unfabricated; exhaustion determinism +
  never-Unavailable; boundedness stall).
- Lab suite: **58/58 PASS** (51 existing + 7 new). Host suite:
  re-run in background (§19 flow).
- Nothing weakened/deleted; crypto tests untouched. Full NDK build
  not run on Termux (existing SDK/network constraint — reported).

## 17. Proven

Recorded-evidence → translator → pollable source → consumer →
snapshot works end-to-end on both real captures with exact counts,
verbatim content, preserved order, metadata-only unknowns, counted
malformed skips, and deterministic exhaustion — zero live surface.

## 18. Not proven

Live production observation (no source — by design); U1 content;
anything beyond the two event types; any consumption of the snapshot.

## 19. Unknown

Carryovers only: U1 class, clock-exact attribution, ack semantics
(untouched by this stage).

## 20. Next step

The read-only chain is complete on recorded evidence. The only
remaining gap is a verified live read-only producer — explicitly out
of scope until directed. No adapter, provider, JNI, registry, or
permission work belongs before that producer is itself verified
against the same translator/consumer contract.

This is a recorded-evidence producer only. It does not connect to
Minecraft. It does not provide live runtime observations.
