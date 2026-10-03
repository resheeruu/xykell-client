# Stage-9G encrypted frame analysis (Minecraft 1.26.52.3, read-only)

## 1. Objective
Explain the real encrypted session: handshake completed locally, yet no
decrypted application traffic was observed.

## 2. Version / 3. Capture
1.26.52.3. capture-2026-10-03T04-44-36-666Z.jsonl, 1821 bytes, 4 records
(all read in full; file untouched).

## 4. Frame timeline (capture order = authoritative)
1. Outbound 359B: enableencryption commandRequest (our ephemeral P-384
   DER + 16B salt), requestId hs-conn-1. PROVEN sent.
2. Inbound 270B: {body:{publicKey:<client ephemeral DER>},
   header:{messagePurpose:"ws:encrypt", requestId:hs-conn-1,
   version:17104896}}. Client key delivery. PROVEN.
3. Inbound 275B: same publicKey; header messagePurpose
   "commandResponse", same requestId. Command result wrapper. PROVEN.
4. Inbound 156B BINARY opcode (base64 in capture), parsed false,
   UNKNOWN. Opaque application bytes. PROVEN present, content UNKNOWN
   (retroactive decryption impossible: ephemeral server key gone with
   the lab process; recorded as opaque, correctly).

## 5–7. Three layers
- Transport: PROVEN (upgrade + 4 frames delivered both directions).
- Encrypted session: LOCALLY established (key derived from records 2/3
  per the documented ECDH→SHA256→AES-CFB8 construction). Correctness of
  OUR key/IV framing vs MC's is NOT independently proven.
- Application: NOT PROVEN — no decrypted valid JSON was ever produced.

## 8. Parse-failure root causes (code-verified, not fixed per mandate)
(a) Binary frames bypass decryption entirely (decrypt runs only on text
frames) → the 156B frame could never parse. (b) `sendOutbound` calls
`ws.send` BEFORE `JSON.parse(frame)` for its capture log → encrypted
subscribe bytes WERE transmitted, then parse threw
("Unexpected token ... is not valid JSON") → the two HANDSHAKE-FAIL
lines are LOGGING crashes, not handshake failures. (c) Race: records 2
and 3 BOTH satisfy the handshake predicate 4ms apart (second arrives
before the first `await` resolves) → double establishment, double
subscribe batches, second batch reusing keystream prefix (same key+IV0)
→ MC-side decrypt of batch 2 likely garbage. Only the FIRST subscribe
(PlayerMessage) per batch reached the socket; PlayerTravelled never
transmitted (crash on first frame each time). These are STRONG
(code-path-proven) findings about OUR lab, not about Minecraft.

## 9. Frame forensics
- 156B binary: direction MC→lab, post-handshake, opaque. Possibly an
  encrypted event/error; undecodable now. NOT called an event.
- 270B: key-delivery with proprietary `ws:encrypt` purpose → classifier
  OTHER is correct-but-shallow (shape recognized, semantics from docs).
- 275B: standard commandResponse shape → classification correct; proves
  command ROUND-TRIP (not execution of gameplay — it carried a key, not
  a game action).

## 10. Subscription ordering (actual vs expected)
Expected: establish → send both subscribes → receive events. Actual:
establish#1 → PlayerMessage(encrypted) sent → log crash; establish#2
(same key material) → PlayerMessage(encrypted) sent again → crash;
PlayerTravelled never sent in either batch. MC received exactly one
subscribe type, twice, second copy under reused keystream.

## 11. Implementation comparison (mcwss)
mcwss decrypts Text AND Binary frames after session start; enables
encryption once per connection (first packet only); sends subscribes
post-establishment as plaintext-then-encrypt at send time with logging
on ciphertext length, never JSON-parsing ciphertext. Our three
deviations (a–c) fully explain the observed transcript without invoking
any MC-side anomaly.

## 12–13. Facts / evidence / inference
DOCUMENTED: wsencrypt offer, enableencryption command, ECDH→SHA256→CFB8,
subscribe-then-events model. LOCAL: transport, key exchange (both keys
on the wire), correlated errors resolved (no more "Encrypted session
required" — the gate OPENED), 156B opaque inbound bytes. INFERENCE
(strong): gate opened; lab-side logging/decrypt gaps explain the rest.

## 14. Unknowns
Whether MC accepted the subscribes (its decrypt of batch 1 unconfirmed);
156B plaintext; correct binary-vs-text opcode convention; whether
single-establishment + decrypt-all-frames yields events.

## 15. Security review
No private key persisted/captured/logged (ephemeral, process-gone);
salt+public keys are public handshake material; no credentials/tokens
in any record (scanned); no Exec beyond enableencryption; no injection.

## 16. Unknowns restated
See §14. Nothing was promoted: Player/World/Entity/Inventory/Chat all
remain NOT OBSERVED as game-state evidence.

## 17. (See §15.)

## 18. Decision gate: PATH B
Encrypted session works (gate opened, keying completed on both sides);
LAB DECRYPTION/PARSING IS INCORRECT in three documented ways. Do NOT
fix yet per mandate. Next: single-establishment guard, decrypt binary
frames, log ciphertext length instead of parsing it — then re-run the
identical manual experiment.
