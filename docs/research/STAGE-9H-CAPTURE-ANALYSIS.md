# Stage-9H capture analysis (capture-2026-10-03T05-25-11-246Z, 6 records)

## PROVEN
- Transport + handshake: outbound 359B enableencryption (plaintext,
  parsed), inbound 270B `ws:encrypt` + 275B `commandResponse` carrying
  the same client ephemeral public key (both parsed, requestId hs-conn-1).
- Single establishment: exactly one subscribe batch — PlayerMessage
  (172B) + PlayerTravelled (174B), distinct fresh UUIDs — with no
  duplicate batch. Double-establishment race did NOT recur.
- Subscribes went out encrypted: the batch emits only from the
  post-establishment path where `hs.session` is non-null, and that path
  encrypts. (Code-path inference from artifact; wire bytes are same
  length as plaintext under the stream cipher, so lengths 172/174 are
  consistent either way — the path, not the lengths, proves it.)
- Capture records for outbound encrypted frames store PLAINTEXT semantics
  (`raw` = plaintext JSON, parsed true) by design; wire ciphertext is
  counted in length only.

## NOT OBSERVED
- No inbound acknowledgement of either subscription.
- No decrypted application JSON; no PlayerMessage/Travelled payloads;
  no game-state fields of any kind.
- No HANDSHAKE-FAIL lines implied by this file (no error records).

## UNKNOWN
- 156B inbound binary frame content (opaque; see below).
- Whether MC successfully decrypted our subscribes.

## FRAME TIMELINE
1. .899 out 359B handshake (plaintext, parsed).
2. .138 in 270B key (`ws:encrypt`, parsed) → claim set synchronously,
   async derive begins.
3. .144 in 275B key (`commandResponse`, parsed) → claim already held,
   ignored for establishment (guard working).
4. .145 in 156B BINARY, base64-recorded, parsed false.
5. .156 out 172B PlayerMessage subscribe (recorded plaintext).
6. .157 out 174B PlayerTravelled subscribe (recorded plaintext).

## 156B FRAME ANALYSIS
- Direction MC→lab, wire 156B, binary opcode, recorded base64.
- It arrived 11ms BEFORE the subscribe batch, which is emitted
  synchronously upon establishment: establishment therefore completed
  between .145 and .156, AFTER the 156 arrived. Hence the 156 was
  decoded with `hs.session == null` → base64 passthrough, UNKNOWN, and
  decryption was NEVER attempted on it. (Strong timing+code-path
  inference; the file itself carries no decrypt-attempt marker.)
- Content hypotheses (all UNKNOWN): MC's first encrypted application
  frame (plausible — MC held our key since record 1 and may encrypt
  immediately); handshake confirmation; control frame. NOT called an
  event. Permanently opaque: the ephemeral session key is gone, so no
  retroactive decryption is possible.

## DECRYPTION PATH
- `decodeInbound` was exercised only in no-session form for this
  connection (records 2–4 predate the session). Post-establishment, no
  further inbound frames arrived, so the decrypt branch ran zero times
  against Minecraft traffic. The 9H fixes are therefore NOT yet proven
  against real encrypted bytes — only against synthetic tests.
- Capture-format gap (documented, not changed): records store no
  classification, no encrypted/session flags, no decrypt outcome —
  those exist only in console output, which was not supplied. A future
  decrypted payload would appear in the file as parsed JSON, but a
  failed decrypt is indistinguishable in-file from never-attempted.

## SUBSCRIPTION ANALYSIS
- Both subscribes sent exactly once, correct documented envelopes,
  fresh requestIds. Acknowledgement: NOT OBSERVED (no inbound records
  after .157). MC-side receipt/decrypt of our subscribes: UNKNOWN.

## SECURITY
- Outbound handshake contains our ephemeral public key + salt (public
  material, safe). Inbound keys likewise ephemeral. No secret-like keys
  in any record (redactedKeys empty throughout; scan-clean). Private key
  never left lab memory. No credentials/tokens anywhere.

## DECISION
- "DECRYPTION NOT PROVEN — LAB FIX STILL INCOMPLETE" as applied to live
  traffic: the fixes held structurally (single establishment, no
  ciphertext parse crashes, correct ordering), but no real encrypted
  application frame has yet passed through the decrypt branch. The 156B
  frame is the prime target: the next experiment must keep the session
  open longer (chat/move/wait AFTER subscribes) so post-establishment
  inbound frames exercise decryption.
