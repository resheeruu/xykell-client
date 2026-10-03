# Stage-9N post-establishment correlation (Minecraft 1.26.52.3, read-only)

Objective: decrypt the first post-establishment encrypted frames and
test them against the subscribed `PlayerMessage` / `PlayerTravelled`
events, with timestamped chat/movement correlation. No crypto changed,
no protocol variant, no Exec, no production code touched, no
commit/push. No keys, salts, derived secrets, or credentials in this
report — only structural metadata and verified envelope shapes.

## 1. OBJECTIVE

As stated above, using the Stage-9L corrected lab (standard unpadded
Base64 handshake strings) with timed ordinary actions (one short chat,
~2 s walk, stop, waits) after verified establishment + subscription.

## 2. STAGE 9M BASELINE

9M proved: connection (outcome A), corrected-encoding acceptance (no
`badsalt`, no bad-key), exactly-once establishment, exactly-once
subscribes, one 156B pre-establishment binary frame, and one 228B
post-establishment frame that produced `DECRYPTION_ERROR` (0/1
decrypted, no event). 9N repeats the same lab build to test whether
decryption succeeds on action-correlated traffic.

## 3. TEST ENVIRONMENT

- Preflight: `git status --short` showed only intended lab/research
  work (no production entries); `npm test` **38/38 PASS** (fail 0).
  Proceeded.
- Instrumentation decision: NO code change. Existing artifacts already
  record connection/establishment/subscribe states (terminal),
  per-record ISO timestamps + direction + length + parsed flags
  (capture), and per-frame decrypt outcome (`encrypted=true` +
  classification in terminal; plaintext JSON in capture on success).
  Only the decrypt-failed-vs-parse-failed sub-detail is absent and is
  recorded as such below.
- Listener: `SUBSCRIBE_ONLY=1 ENCRYPTED_SESSION=1 npm start`,
  127.0.0.1:8765, under `termux-wake-lock`; session file
  `captures/capture-2026-10-03T08-22-47-561Z.jsonl`; log
  `$PREFIX/tmp/xykell-9n-terminal.log`.
- First attempt caveat (recorded honestly): the initial ungated run
  produced no connection (user performed game steps without checking
  the terminal; log stayed banner-only). The SAME live listener was
  then used for a properly gated retry (bare `/wsserver`, exact
  `/wsserver ws://127.0.0.1:8765`, terminal watched through
  `[SUBSCRIBED]` before any game action). All evidence below is from
  that single gated session (`conn-1`); no gameplay preceded
  establishment/subscription.

## 4. TIMELINE

Capture-authoritative (ISO, ms resolution). Terminal states agree.

| Timestamp (UTC) | Direction | Length | State | Result |
|---|---|---|---|---|
| 08:26:44.258 | lab → MC | 359 | handshake | `enableencryption` (key 160 chars std, salt 22 chars std) |
| 08:26:44.357 | MC → lab | 270 | handshake | `ws:encrypt` key delivery (client publicKey) |
| 08:26:44.362 | MC → lab | 275 | handshake | `commandResponse` key delivery (duplicate, ignored by guard) |
| 08:26:44.365 | MC → lab | 156 | pre-establishment | binary opaque (19 ms before subscribes; decrypt never attempted) |
| 08:26:44.384 | lab → MC | 172 | established | PlayerMessage subscribe (fresh UUID) |
| 08:26:44.385 | lab → MC | 174 | established | PlayerTravelled subscribe (fresh UUID) |
| 08:27:10.310 | MC → lab | 540 | established (+26 s) | encrypted frame → `DECRYPTION_ERROR` |
| 08:27:10.505 | MC → lab | 405 | established (+26 s) | decrypted → PlayerTravelled envelope |
| 08:27:15.941 | MC → lab | 158 | established (+31 s) | decrypted → PlayerMessage envelope |

Terminal corroboration: `[CONNECTED]`, `awaiting-key`,
`COMMAND_RESPONSE` 275 parsed, `UNKNOWN` 156 unparsed, `established`,
`SUBSCRIBE` ×2, `[SUBSCRIBED]`, `OTHER` 270 parsed, then
`DECRYPTION_ERROR` 540 `encrypted=true`, `PLAYER` 405 `encrypted=true`,
`CHAT` 158 `encrypted=true`. Lab stopped after the window.

## 5. SUBSCRIPTION STATUS

Both subscribes sent exactly once, post-establishment, correct
documented envelopes with fresh UUIDs (`4142afb1-…` PlayerMessage,
`626c75a3-…` PlayerTravelled). No duplicates, no additions. No
subscription acknowledgement frames observed (none reference the
subscribe requestIds).

## 6. POST-ESTABLISHMENT FRAMES

Three inbound frames arrived after `state=established` (session object
present for all three):

- frame length: 540, timestamp: 08:27:10.310Z, decrypt attempted: YES,
  decrypt result: `DECRYPTION_ERROR`, plaintext length: n/a,
  JSON parse: NO, classification: `DECRYPTION_ERROR`, event: none
  (opaque; wire bytes base64-preserved in capture).
- frame length: 405, timestamp: 08:27:10.505Z, decrypt attempted: YES,
  decrypt result: SUCCESS, plaintext length: 405,
  JSON parse: YES, classification: `PLAYER`, event: PlayerTravelled
  (verified envelope, §9).
- frame length: 158, timestamp: 08:27:15.941Z, decrypt attempted: YES,
  decrypt result: SUCCESS, plaintext length: 158,
  JSON parse: YES, classification: `CHAT`, event: PlayerMessage
  (verified envelope, §9).

## 7. DECRYPTION ATTEMPTS

For every post-establishment frame: session object present (established
08:26:44.693Z terminal / subscribes 08:26:44.384–385Z capture, all
frames ≥26 s later); `decodeInbound()` ran (single choke point);
decryption attempted 3/3. Results: 1 × `DECRYPTION_ERROR` (540B),
2 × success (405B, 158B). Success rate on real post-establishment
bytes this session: 2/3; cumulative across stages: 2/4 (9M 228B failed,
9N 540B failed, 9N 405B + 158B decrypted).

## 8. DECRYPTION RESULTS

- 540B frame: failed. Decrypt-failed vs parse-failed is
  indistinguishable in the artifacts (lab records the category, not
  the `error` detail) — recorded as unknown sub-cause, NOT retried,
  NOT re-interpreted. Arrived 195 ms before the successfully decrypted
  405B frame on the same session (same key/IV stream position region),
  so total session-key mismatch is excluded as the explanation for
  this single frame; per-frame cause remains open (framing, opcode
  path, or payload class the current `decodeInbound` does not model).
- 405B + 158B frames: decrypted to valid UTF-8 JSON application
  envelopes (captured as parsed plaintext records, §9). The
  ECDH → SHA-256 → CFB8 construction as implemented decrypts real
  Minecraft application traffic — first successful real-traffic
  decryption in the program.

## 9. JSON/PAYLOAD ANALYSIS

405B plaintext (verified parsed record):

- `header`: `{eventName: "PlayerTravelled", messagePurpose: "event",
  version: 17104896}` — valid application envelope.
- `body`: `{isUnderwater: false, metersTravelled: 1.054285049438477,
  newBiome: 0, player: {color, dimension: 0, id, name: "Xykeel",
  position: {x: -485.5568237304688, y: 64.62001037597656,
  z: -370.8374328613281}, type: "minecraft:player", variant: 0,
  yRot: -7.316070556640625}, travelMethod: 0}`.

158B plaintext (verified parsed record):

- `header`: `{eventName: "PlayerMessage", messagePurpose: "event",
  version: 17104896}` — valid application envelope.
- `body`: `{message: "hiiii", receiver: "", sender: "Xykeel",
  type: "chat"}`.

Both envelopes match the documented Bedrock event shape
(`header` + `body`, `messagePurpose: "event"`, named `eventName`).
Shapes are reported as lab observations; NOTHING is promoted into the
Xykell runtime (no adapter built this stage).

## 10. EVENT CLASSIFICATION

- 405B → `PLAYER` (classifier: `Player*` non-`PlayerMessage` event;
  shape-only, per classifier contract): PlayerTravelled — RECOGNIZED,
  envelope-verified.
- 158B → `CHAT` (classifier: `PlayerMessage`): PlayerMessage —
  RECOGNIZED, envelope-verified.
- 540B → `DECRYPTION_ERROR`: not an event, not called one.
- 156B (pre-establishment) → `UNKNOWN`: not an event (4th occurrence
  of the pattern; standing rule upheld).

## 11. CHAT CORRELATION

A `PlayerMessage` chat envelope (`sender: "Xykeel"`, `type: "chat"`)
arrived 08:27:15.941Z, ≈31 s after subscribes, inside the window in
which the user performed the single-chat step. Content/plausibility
support correlation. Two honesty notes: (a) no second-precision user
clock time for the keypress was supplied, so correlation is SUPPORTED,
not clock-proven; (b) the instructed text was `9N_TEST` but the
observed message is `hiiii` — the chat step demonstrably produced a
chat event on this subscribed session, but the literal string differs
(typing variance or an additional message; NOT treated as a mismatch
failure, recorded as observed).

## 12. MOVEMENT CORRELATION

A `PlayerTravelled` envelope arrived 08:27:10.505Z, ≈26 s after
subscribes, inside the walk/stop window, carrying
`metersTravelled: 1.054…` with a full player position/rotation —
content consistent with a short walk-then-stop. Same clock caveat as
§11: SUPPORTED by content + window timing, not second-precision
proven. The co-timed 540B failure (195 ms earlier) is explicitly NOT
folded into the movement claim.

## 13. PROVEN

- Real post-establishment frames decrypt with the as-implemented
  ECDH → SHA-256 → CFB8 construction (2/3 this session).
- Subscribed `PlayerTravelled` and `PlayerMessage` event envelopes
  arrive as valid JSON with documented shapes (405B/158B, verified
  records above).
- Exactly-once establishment + single subscribe batch held again
  (4th accepted session with the same transcript shape).
- The 9L standard-Base64 handshake is accepted without errors for the
  second consecutive session.
- No code change was needed for any of this (zero modifications this
  stage, including zero instrumentation — existing timestamps sufficed).

## 14. NOT PROVEN

- Per-frame cause of the 540B `DECRYPTION_ERROR` (and by extension the
  9M 228B one): decrypt- vs parse-failed unknown; no second attempt
  made.
- Clock-synchronized action→frame attribution (user clock times for
  chat/walk steps were not supplied).
- Subscription acceptance semantics (still no ack frames).
- Anything about unobserved event types, world/entity state, or
  runtime promotion (no adapter; nothing promoted).

## 15. UNKNOWN

- 540B frame content and failure sub-cause (opaque; key gone with the
  stopped process, same standing rule).
- Exact game-side text of the 9N `/wsserver` (not captured this stage;
  lab-side evidence is conclusive regardless).
- Why the instructed `9N_TEST` string differs from the observed
  `hiiii` message content.
- Generalization beyond the two verified envelopes (one session, two
  events).

## 16. CRYPTO IMPLEMENTATION OBSERVATIONS

(Evidence collection only — NO changes made, per mandate. No new
variant proposed here.)

- Against the stage-8 checklist: shared-secret derivation ran (client
  P-384 key parsed — 270/275B deliveries accepted, session established
  and demonstrably decrypting), so key parsing, curve, salt inclusion,
  SHA-256→32 B key, 16 B IVs, and CFB8 streaming direction are
  functionally corroborated by the two successful decrypts — a fully
  wrong construction would not yield two valid JSON envelopes.
- The 540B failure on the same live session bounds the defect (if any)
  to something per-frame rather than per-session: candidate classes
  are frame-type/opcode handling, a multi-frame or fragmented payload,
  or a payload class outside the text/binary→decrypt→UTF-8→JSON path —
  all UNKNOWN pending the focused differential analysis the stage
  prescribes. Synthetic round-trips continue to pass (38/38) and are,
  as required, NOT treated as interoperability proof — the real proof
  is §9, and the real gap is the 540B frame.

## 17. CONCLUSION

First real-traffic decryption achieved: 2/3 post-establishment frames
decrypted to envelope-verified subscribed events (PlayerTravelled +
PlayerMessage) correlated by content and window timing to the
performed walk and chat; 1/3 (540B) failed with `DECRYPTION_ERROR` for
an undetermined per-frame cause. No protocol error appeared; no crypto
change was made (and none is triggered by this result). Nothing
promoted to game-state/runtime evidence beyond lab-observed verified
envelopes.

## 18. NEXT MINIMAL STEP

Focused differential analysis of the 540B-class failure against the
documented construction (single question: what distinguishes the
failing frame — length class ≥~500B, opcode, fragmentation, or payload
type — given the same session decrypts adjacent frames). Concretely:
(a) one more gated session with second-precision user clock times for
each action and the game-side `/wsserver` text transcribed, to harden
correlation; (b) read-only comparison of failing vs succeeding frame
length classes across all captures (156/228/540 vs 158/270/275/405 —
no new crypto); (c) if a length/pattern split emerges, FIRST add
observability (log the already-computed decrypt `error` detail and
plaintext length on failure paths — observability, not a protocol
change), then propose at most one minimal correction backed by the
mcwss reference. If valid envelopes keep arriving: STOP crypto work and
analyze envelopes (still no adapter until directed).

Security: zero modifications (lab, protocol, production verified via
`git status`); no keys/salts/secrets derived, printed, or persisted
(structural lengths + verified envelope shapes only; player name,
position, and chat text above are the observed game-event content the
stage explicitly tasked us to verify, from the user's own test world);
localhost-only, observation/subscription-only, no Exec/injection/
interception/bypass; no commit/push.
