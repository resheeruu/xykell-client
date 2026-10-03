# Stage-9O 540B failing-frame differential (read-only analysis)

Objective: explain the single post-establishment frame that failed in
9N (terminal label "540B") against the two succeeding frames from the
same session as controls — without changing crypto, protocol,
production, or anything else. No live session was needed (existing
evidence distinguishes the failure stage). No commit/push. Only
non-reversible digests and structural metadata below; no keys, IVs,
secrets, credentials, tokens, or raw ciphertext.

## 1. OBJECTIVE

As stated above. Standing rule kept throughout: the failing frame is
`UNKNOWN_FRAME` — never an event by size, timing, or correlation.

## 2. STAGE-9N BASELINE

9N (capture `...08-22-47-561Z.jsonl`, 9 records): connection,
corrected-encoding acceptance, exactly-once establishment, one
subscribe batch, 156-class pre-establishment binary, then three
post-establishment inbound frames — 540B-label → `DECRYPTION_ERROR`,
405B → decrypted PlayerTravelled, 158B → decrypted PlayerMessage
(both envelope-verified). Construction therefore decrypts real traffic;
the 540B-label frame is a per-frame, not per-session, phenomenon.

## 3. TEST RESULTS

- Preflight `git status --short`: only intended lab/research work, no
  production entries. `npm test`: **38/38 PASS** (fail 0). Proceeded.
- No code modified in this stage — including no observability change
  (justified in §9: the error-stage distinction was established by
  construction plus the throw-test, so no instrumentation was
  "genuinely required").

## 4. FRAME INVENTORY

Length-label correction (applies to ALL stages): the capture
`length` field — and hence the terminal `bytes=` line and every
"156B/228B/540B" label to date — measures the base64 TEXT length of
binary records (`makeRecord` uses `raw.length` after base64 encoding),
not wire bytes. True wire lengths (verified by decoding, §10):

| Terminal/capture label | base64 chars | true wire bytes |
|---|---|---|
| 156B (×4 sessions) | 156 | **115** |
| 228B (9M post-est.) | 228 | **171** |
| 540B (9N post-est.) | 540 | **405** |

(Post-establishment successes: 405-char and 158-char plaintexts came
from 405- and 158-byte wire frames respectively — stream-cipher
length preservation holds.)

Differential table, post-establishment inbound only (9N session):

| Property | 540B-label | 405B | 158B |
|---|---|---|---|
| timestamp | 08:27:10.310Z | 08:27:10.505Z | 08:27:15.941Z |
| direction | MC → lab | MC → lab | MC → lab |
| wire length | 405 bytes (540 b64 chars) | 405 bytes | 158 bytes |
| encoding | binary opcode | binary opcode | binary opcode |
| session established | YES (+26 s) | YES (+26 s) | YES (+31 s) |
| decrypt attempted | YES | YES | YES |
| decrypt result | no exception; output non-JSON (see §9) | SUCCESS | SUCCESS |
| plaintext length | 405 bytes (stream-cipher length preservation) | 405 chars | 158 chars |
| UTF-8 valid | n/a (see §9 — `toString` never throws; validity untested, irrelevant) | YES (implicit — parsed) | YES (implicit — parsed) |
| JSON valid | NO | YES | YES |
| message purpose | — | `event` | `event` |
| event name | — (`UNKNOWN_FRAME`) | PlayerTravelled | PlayerMessage |

No event type is inferred from size at any point.

## 5. 540B FRAME

- True wire: 405 bytes, sha256 `ef54981a460a…` (non-reversible
  fingerprint; unique across all captures — not a duplicate/replay).
- Arrived 08:27:10.310Z, ≈26 s after subscribes, 195 ms BEFORE the
  successfully decrypted 405-char PlayerTravelled frame on the same
  live session.
- Terminal: `DECRYPTION_ERROR`, `parsed=false`, `encrypted=true`.
  Capture: base64 record, `parsed=false`.
- Verdict: `UNKNOWN_FRAME`. Not malformed-by-assumption, not JSON,
  not an event.

## 6. 405B CONTROL

Wire 405 bytes → decrypted 405-char UTF-8 JSON →
`header {eventName: PlayerTravelled, messagePurpose: event}` +
travel body (`metersTravelled: 1.054…`, player position/rotation —
verified in 9N). Classification `PLAYER`. Same session, same
key/IV-stream, 195 ms after the failing frame.

## 7. 158B CONTROL

Wire 158 bytes → decrypted 158-char UTF-8 JSON →
`header {eventName: PlayerMessage, messagePurpose: event}` +
chat body (`message: "hiiii"`, sender, `type: chat` — verified in
9N). Classification `CHAT`. Same session, ≈5 s later.

## 8. DECRYPTION PATH

`decodeInbound(bytes, isBinary, session)` with non-null session:
`session.decrypt(bytes)` → `toString('utf8')` → `tryParseJson` →
`classify`, else `DECRYPTION_ERROR`. Two internal failure exits share
that label: exception from `decrypt` ("decrypt-failed") vs
`JSON.parse` throw ("parse-failed"). The artifacts do not separate
them — but §9 does, without touching code.

## 9. ERROR LOCATION

540B:
  decrypt: RAN, no exception possible (see below)
  UTF-8: `toString` cannot throw; not a failure stage
  JSON: FAILED ← failure stage (JSON_PARSE_ERROR)
  classification: `DECRYPTION_ERROR` (umbrella label, stage 6 of 7)
  failure stage: 6. JSON parsing

405B:
  decrypt: SUCCESS
  UTF-8: valid (parsed)
  JSON: valid
  classification: `PLAYER` (PlayerTravelled)

158B:
  decrypt: SUCCESS
  UTF-8: valid (parsed)
  JSON: valid
  classification: `CHAT` (PlayerMessage)

How the stage is known without new instrumentation:

1. CFB8 `decipher.update()` cannot throw on arbitrary bytes (stream
   XOR, no padding, no auth). Verified empirically with a synthetic
   throwaway key (no session material): 7 lengths incl. 0/115/171/
   405/540/1024 — **0 throws**, output length always equals input
   length; non-UTF8 output survives `toString` and fails `JSON.parse`
   exactly as the lab handles it. The "decrypt-failed" exception exit
   is therefore unreachable for well-formed ws Buffers, and stages
   1–5 (reception, buffer conversion, extraction, CFB8, UTF-8 decode)
   have no throwing operation on this path. The umbrella label is
   trusted as to outcome but refined as to stage: JSON parsing.
2. Consequence for the "too-broad catch" question (§4 of the task):
   the catch is broad but HARMLESS here — the only reachable failure
   is the parse, and no observability change was required to show it.
   (If a future frame ever trips a genuine decrypt exception, THAT is
   when the `error`-detail logging becomes necessary.)

## 10. CAPTURE INTEGRITY

PROVEN lossless for both binary records in 9N: base64 decode yields
exactly `length`-consistent wire bytes (115 and 405), and re-encoding
reproduces the stored `raw` character-for-character
(`roundtrip_exact=True`). The capture layer therefore delivered to
`decodeInbound()` exactly the bytes the socket gave it: no truncation
(received lengths match `Buffer.from(data)` input), no binary→UTF-8
conversion (binary opcode frames take the base64 branch), no added/
removed bytes, no boundary changes. The 540-label frame failed on its
own merits, not on capture corruption. (Node base64 output is canonical,
so exact re-encode match additionally excludes alphabet/whitespace
artifacts.)

## 11. SESSION STATE CONTINUITY

Code-verified (read-only; identifiers redacted to stable non-secret
handles):

- session present for all three frames: YES (single `conn-1` session
  in capture `...08-22-47-561Z`; establishment terminal-logged before
  all three).
- session identity: `conn-1` throughout (no reconnect, no second
  `hs-conn-*`, exactly-once guard held — duplicate 275B key ignored).
- key state: UNCHANGED (assigned once post-derive; no reassignment
  path exists).
- IV state: UNCHANGED as construction (separate `Buffer.from(iv)`
  copies for encipher/decipher at creation; nothing re-seeds them).
- cipher state: ADVANCED NORMALLY (streaming decipher consumed 115
  pre-session? No — pre-session frames bypass decrypt; post-session it
  consumed 405 + 405 + 158 bytes in order, exactly as a persistent
  stream must).
- No reset/mutation path exists between frames: outbound subscribes
  use the separate encipher object and cannot perturb decipher state.

## 12. CFB8/STREAM-STATE ANALYSIS

The implementation treats each WebSocket frame as **(B) a continuation
of a persistent encrypted stream**: one decipher per connection,
state carried across frames (byte-chained CFB8). This matches the
documented construction (mcwss `encryptionSession` persists per player;
`decrypt` chains `decryptIV` per byte across messages; `server.go`
decrypts every inbound text/binary frame with it). No discrepancy.

Decisive stream-continuity argument: the failing frame's 405 wire
bytes were CONSUMED by the live decipher (405 keystream bytes), after
which the next two frames decrypted to valid JSON. Had those 405 bytes
been plaintext-binary, a foreign-key stream, or otherwise
out-of-stream, the keystream position would have desynchronized and
the 405-char/158-char frames would have decrypted to garbage. They did
not. Therefore the failing bytes ARE genuine in-stream ciphertext
whose 405-byte plaintext is simply not valid JSON (non-JSON payload,
multi-message framing that `JSON.parse` rejects as a whole, or another
payload class outside text-envelope modeling — content UNKNOWN, and
the JSON failure is a property of the plaintext, not of the cipher).

## 13. DOCUMENTED PROTOCOL COMPARISON

- mcwss `server.go`: decrypts every inbound frame with the persistent
  session, then `json.Unmarshal`; malformed JSON logs "malformed
  packet JSON" and BREAKS the connection loop (fatal). Our lab records
  `DECRYPTION_ERROR` and CONTINUES — strictly more observational (a
  fatal break would have hidden the two succeeding events). Lab-only
  leniency, not a protocol deviation; no change proposed.
- mcwss `player.go`/`encryption.go`: per-byte-chained CFB with
  independent encrypt/decrypt IVs from `key[:16]` — identical in
  structure to `createEncryptedSession` (separate cipher objects, IV
  copies). No discrepancy found.
- 9F/9H research construction (ECDH P-384 x → SHA-256(salt+secret) →
  CFB8, IVs = key[:16]) is corroborated, not indicted: two valid
  envelopes from the same stream prove end-to-end key/IV correctness.

## 14. PROVEN

- "540B" = 405 wire bytes (label correction, verified by decode).
- Capture path is bit-lossless for binary frames (exact roundtrip).
- All binary ciphertexts are session-unique (sha prefixes 1114…/988…/
  46f8…/6fe0…/ec6a…/ef54… — no duplicates, no replays).
- Failure stage is JSON parsing, not reception/buffer/CFB8/UTF-8
  (0-throw CFB property + throw-test; no code change needed to show it).
- Failing bytes are genuine in-stream ciphertext (stream-continuity:
  successors decrypt cleanly), with non-JSON plaintext (content
  unknown).
- Session/key/IV/cipher state continuous and unmutated across all three
  frames; implementation is persistent-stream (B), matching mcwss.
- No failure at handshake, derivation, subscription, transport, or
  capture layers for this frame.

## 15. NOT PROVEN

- Plaintext content/class of the failing frame (non-JSON established;
  beyond that UNKNOWN — never labeled an event or malformed).
- Whether its non-JSON-ness is a binary payload type, concatenated
  messages, fragmentation, or an unmodeled frame class.
- Clock-synced attribution of any frame to a specific user action
  (carried over from 9N).
- Anything beyond this session's three frames.

## 16. UNKNOWN

- Failing-frame plaintext bytes and payload type (key gone with the
  stopped process; retroactive decryption impossible — standing rule).
- 9M's 171-wire `DECRYPTION_ERROR` frame sub-cause (same JSON-stage
  reasoning applies by construction, but it is a separate session
  without succeeding controls — noted, not merged).
- Whether future sessions will show more non-JSON frames and at what
  rate/positions.

## 17. CONCLUSION

The 540B-label (405-wire-byte) failure is EXPLAINED without touching
cryptography: genuine in-stream ciphertext, correctly consumed by a
continuous CFB8 decipher, whose plaintext is not valid JSON — hence
`DECRYPTION_ERROR` at the JSON-parse stage, with session, key, IV,
transport, and capture all exonerated by positive evidence (two
adjacent successes + lossless roundtrip + stream continuity). Per-frame
phenomenon, not session-wide (frames after it decrypt successfully —
the decision rule's exact criterion). The construction stands as
implemented; the umbrella label is accurate as to outcome. `UNKNOWN_FRAME`
retained for content. This is NOT a hard-stop condition (no protocol
error appeared).

## 18. NEXT MINIMAL STEP

No crypto/protocol/capture change. Two read-only follow-ups, smallest
first: (a) accumulate length-class statistics across future gated
sessions (do non-JSON wire frames cluster at specific lengths/positions
relative to actions? — pure observation); (b) IF a second failing frame
is ever captured on a session with succeeding controls, consider ONLY
the already-scoped observability addition (persist the existing
`error` detail + plaintext byte length on failure paths — it changes
no behavior, only records which of the two already-known exits fired).
If valid envelopes keep arriving alongside occasional non-JSON frames:
STOP crypto work permanently and analyze envelopes (still no adapter
until directed).

Security: observation-only boundary held throughout — no crypto,
protocol, production, or capture-format changes (verified: zero
modifications this stage); digests/metadata only, no ciphertext
reproduced beyond what the captures already store, no plaintext
recovered or printed, no key/IV/secret material handled at any point
(the throw-test used a synthetic throwaway key); no injection,
rewriting, interception, bypass, or exploit; localhost-only; no
commit/push.
