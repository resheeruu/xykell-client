# Stage-9H encrypted frame fixes (lab mechanics verified; Minecraft pending)

## Exact bugs found (Stage 9G evidence)
1. Double establishment: two key frames 4ms apart both passed the
   predicate (second arrived during the first `await`), re-deriving the
   session, resending subscribes, reusing keystream prefix.
2. Binary frames bypassed decryption entirely (decrypt ran on text only).
3. `JSON.parse(ciphertext)`: sendOutbound parsed the wire bytes for its
   capture log — the two HANDSHAKE-FAIL lines were logging crashes AFTER
   `ws.send`, not handshake rejections. Consequence: PlayerMessage sent
   (twice), PlayerTravelled never sent, no SUBSCRIBE records captured.

## Minimal fixes (lab/ only; construction unchanged)
1. `claimEstablishment(state)` in encryption.mjs: synchronous first-claim
   wins; derive failure releases the claim (retry possible), success keeps
   it (exactly-once). Server uses it before the async derive.
2. `decodeInbound(data, isBinary, session)` choke point: decrypt-first
   whenever a session exists (binary AND text), parse only the result;
   DECRYPTION_ERROR category otherwise (new, never an event claim).
3. sendOutbound now takes caller-parsed plaintext JSON + wire bytes
   separately; byte counts logged are wire lengths; ciphertext is never
   parsed. Capture records plaintext semantics + wire length.

## Construction used (unchanged, re-verified vs mcwss)
ECDH P-384 x, SHA256(salt+secret), AES-256-CFB8 streaming both ways,
IVs = key[:16], stateful across frames, per-frame decrypt-then-parse.
Matches mcwss encryption.go/server.go/player.go semantics.

## State machine
CONNECT → HANDSHAKE sent (awaiting-key) → first key response claims →
derive → established (once) → exactly one subscribe batch (both events)
→ per-frame decode (DECRYPTION_ERROR on failure) → close cleanup.
Duplicate key frames: ignored for establishment (logged as MESSAGE).

## Test results
30/30 PASS (11 protocol + 6 subscribe + 13 encryption incl. guard
once/duplicate/established-twice, decode plaintext/binary/encrypted/
mismatch/garbage/unknown-JSON, ciphertext-never-parsed source scan).
Live synthetic-peer check: subprotocol negotiated, handshake-first
ordering, established ×1, HANDSHAKE-FAIL ×0, SUBSCRIBE ×2 (172B+174B
wire), SUBSCRIBED ×1, duplicate key response ignored.

## Manual experiment / frame sizes / decrypt results
PENDING (no manual run yet). 156B-class decrypt, valid JSON, PlayerMessage
/Travelled observation, game-state fields: all UNKNOWN until observed.

## PROVEN / UNKNOWN / NOT OBSERVED
PROVEN (synthetic): guard mechanics, decode paths, crypto roundtrip,
ordering, capture safety. UNKNOWN: MC acceptance, event shapes, opcode
conventions. NOT OBSERVED: any Minecraft application payload.

## Security audit
Privkeys ephemeral/memory-only/non-serializable; no secrets logged or
persisted; no Exec beyond the single handshake command (source-asserted);
no production code touched (app/, native/, registry unchanged).

## Production untouched confirmation
`git status` shows lab/ + research docs only; captures absent (cleaned).

## Decision gate
Lab READY for the identical manual experiment. If 156B-class frames now
decrypt into event envelopes: STOP and analyze envelopes (no adapter).
If new errors appear: record exactly, no workarounds.
