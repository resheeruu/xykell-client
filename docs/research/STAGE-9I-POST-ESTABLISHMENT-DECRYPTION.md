# Stage-9I post-establishment decryption (Minecraft 1.26.52.3, read-only)

Target: prove a real Minecraft encrypted frame arrives AFTER
WebSocket connection + encryption establishment + PlayerMessage
subscription + PlayerTravelled subscription, and trace it through
binary frame -> decodeInbound() -> decrypt -> UTF-8/plaintext
-> JSON parse -> application envelope.

Mandate: do NOT modify Xykell production code, do NOT modify encryption
implementation, do NOT commit/push, no adapter, no field promotion.
No commands sent other than the already-implemented documented
subscription mechanism.

## PROVEN

- Lab code under test is unchanged production-wise: `npm test` 30/30 PASS
  (11 protocol + 6 subscribe + 13 encryption) immediately before the run.
- Clean Stage-9I server started as
  `SUBSCRIBE_ONLY=1 ENCRYPTED_SESSION=1 npm start` on 127.0.0.1:8765,
  mode `OBSERVATION ONLY + SUBSCRIBE_ONLY + ENCRYPTED_SESSION`.
  Startup banner observed in terminal log (398 bytes total).
- New session file created:
  `captures/capture-2026-10-03T07-41-32-208Z.jsonl` (touch at startup).
- Prior Stage-9H captures re-verified byte-for-byte in full (not sampled):
  `capture-2026-10-03T05-25-11-246Z.jsonl` (2557 bytes, 6 records) and
  `capture-2026-10-03T05-32-04-888Z.jsonl` (2557 bytes, 6 records).
  Both show the identical structure with fresh ephemeral keys per session:
  out 359B enableencryption -> in 270B ws:encrypt key -> in 275B
  commandResponse key (same client key) -> in 156B BINARY base64
  (parsed false) -> out 172B PlayerMessage subscribe (plaintext record)
  -> out 174B PlayerTravelled subscribe (plaintext record).
- The 156B frame in both prior captures arrived BEFORE the subscribe batch
  (11ms and 20ms before respectively, per capture timestamps), i.e. BEFORE
  `hs.session` existed. Per `encryption.mjs decodeInbound()`, the decrypt
  branch runs ONLY when `session` is non-null, so decryption was NEVER
  attempted on those 156B frames. This is code-path fact, not inference
  about content.
- Stage-9I window itself produced ZERO inbound frames (see FRAME TIMELINE).
  Therefore the decrypt branch ran ZERO times against Minecraft traffic in
  the 9I window. This is PROVEN by the empty capture (0 lines) plus the
  terminal log containing only the startup banner and `Waiting for
  Minecraft...` with no `[CONNECTED]`, no `[HANDSHAKE]`, no `[SUBSCRIBE]`,
  no `[MESSAGE]` lines.
- Lab TCP reachability: `bash /dev/tcp/127.0.0.1/8765` returned TCP-OK
  while the 9I server was running, proving the listener was bound.
  A Node `ws`-client synthetic connect in the same Termux context failed
  with `EACCES`, a client-side sandbox artifact, not a server refusal
  (server log shows no error, bind succeeded, TCP-OK succeeded).

## NOT OBSERVED

- WebSocket connection in the 9I window: NOT OBSERVED (no `[CONNECTED]`).
- Encryption establishment in the 9I window: NOT OBSERVED (no
  `[HANDSHAKE] ... established`, exactly-once guard never exercised).
- `[SUBSCRIBE] PlayerMessage` / `[SUBSCRIBE] PlayerTravelled` emissions in
  the 9I window: NOT OBSERVED (subscribes are emitted only after
  establishment; establishment never happened).
- Post-establishment inbound frames: NOT OBSERVED (0 lines in
  `capture-2026-10-03T07-41-32-208Z.jsonl`).
- Decrypt attempted / decrypt success / decrypt failure: NOT OBSERVED
  (zero post-establishment frames, so zero attempts).
- Plaintext length, JSON parse success/failure on decrypted output:
  NOT OBSERVED (nothing to parse).
- Decrypted application JSON, application envelope
  (`header.messagePurpose`/`messageType`/`eventName`), PlayerMessage /
  PlayerTravelled payloads: NOT OBSERVED.
- Subscription acknowledgements of any kind: NOT OBSERVED.
- Game-state fields of any kind (player position, chat text, world/entity):
  NOT OBSERVED.
- The 156B frames from the two prior sessions are explicitly NOT claimed
  as events. They are binary, 156B, and temporally near handshake, but
  none of those properties constitutes an event, per the evidence bar.
  Content remains UNKNOWN (ephemeral keys gone, retroactive decryption
  impossible).

## UNKNOWN

- Whether `/wsserver ws://127.0.0.1:8765` from the Minecraft client reached
  this 9I listener at all (no server-side connection record; client-side
  error text was not supplied).
- Content of the two prior 156B pre-establishment binary frames (opaque).
- Whether Minecraft 1.26.52.3 accepted/decrypted our prior subscribe batch
  (no inbound acknowledgement in either prior capture).
- Correct binary-vs-text opcode convention for post-establishment
  application frames (no post-establishment sample exists).
- Whether single-establishment + decrypt-all-frames yields valid event
  envelopes on this target (still unproven against real bytes; synthetic
  tests only).
- Everything in the 13-question evidence list that requires a decrypted
  payload: all UNKNOWN.

## FRAME TIMELINE

### 9I window (authoritative: terminal log + latest capture)

1. `2026-10-03T07:41:32.208Z` server start, capture file
   `capture-2026-10-03T07-41-32-208Z.jsonl` touched (0 bytes).
2. Terminal log (398 bytes, full content preserved below) shows ONLY:
   `=== XYKELL BEDROCK WEBSOCKET LAB ===`, Host/Port/Mode/Captures lines,
   `Waiting for Minecraft...`. No `[CONNECTED]`, no `[HANDSHAKE]`, no
   `[SUBSCRIBE]`/`[SUBSCRIBED]`, no `[MESSAGE]`, no `[DISCONNECTED]`,
   no `[ERROR]`, no `[HANDSHAKE-FAIL]`.
3. Latest capture: 0 lines, 0 bytes. No outbound handshake, no inbound
   key, no 156B-class frame, no subscribes.
4. Lab stopped after the user-confirmed chat/move/wait window; final
   `find captures` shows the 9I file still 0 bytes alongside the two
   unchanged 2557-byte 9H files.

Terminal log (complete, 398 bytes):

```
> xykell-bedrock-websocket-lab@0.1.0 start
> node server.mjs

=== XYKELL BEDROCK WEBSOCKET LAB ===
Host: 127.0.0.1
Port: 8765
Mode:
  OBSERVATION ONLY + SUBSCRIBE_ONLY (read-side events only) + ENCRYPTED_SESSION (documented wsencrypt handshake)
Captures: /data/data/com.termux/files/home/xykell-client/lab/bedrock-websocket/captures/capture-2026-10-03T07-41-32-208Z.jsonl
Waiting for Minecraft...
```

Captures listing at close-out:

```
1791005178.1555574000 capture-2026-10-03T05-25-11-246Z.jsonl 2557 bytes
1791005552.7435574230 capture-2026-10-03T05-32-04-888Z.jsonl 2557 bytes
1791013292.2060497900 capture-2026-10-03T07-41-32-208Z.jsonl 0 bytes
```

### Prior sessions (reference, 6 records each, full read)

`05-25-11` session (timestamps UTC):

1. `05:26:17.899Z` out 359B handshake (plaintext, parsed true).
2. `05:26:18.138Z` in 270B `ws:encrypt` key (parsed true).
3. `05:26:18.144Z` in 275B `commandResponse` key (parsed true, duplicate
   key, ignored for establishment by the exactly-once guard).
4. `05:26:18.145Z` in 156B BINARY base64 (parsed false) — 11ms BEFORE
   subscribes, hence pre-session, decrypt never attempted.
5. `05:26:18.156Z` out 172B PlayerMessage subscribe (plaintext record).
6. `05:26:18.157Z` out 174B PlayerTravelled subscribe (plaintext record).

`05-32-04` session is structurally identical (fresh keys, fresh UUIDs),
with the 156B frame 20ms before subscribes. No inbound records after the
subscribe batch in either file.

## DECRYPTION ATTEMPTS

Per-frame table required by the evidence bar. 9I window: zero rows
(no post-establishment inbound frames existed).

| timestamp | direction | wire len | session established? | decrypt attempted? | decrypt ok? | plaintext len | JSON parse? | classification |
|-----------|-----------|----------|----------------------|--------------------|-------------|---------------|-------------|----------------|
| (none)    | —         | —        | —                    | —                  | —           | —             | —           | —              |

For completeness, prior sessions' only candidate (156B) traced through
`decodeInbound(data, isBinary=true, session=null)`:

| timestamp | direction | wire len | session established? | decrypt attempted? | decrypt ok? | plaintext len | JSON parse? | classification |
|-----------|-----------|----------|----------------------|--------------------|-------------|---------------|-------------|----------------|
| 2026-10-03T05:26:18.145Z | minecraft->xykell-lab | 156 | NO (established ~11ms later) | NO (session null -> base64 passthrough) | n/a | n/a | NO (parsed false) | UNKNOWN |
| 2026-10-03T05:32:32.724Z | minecraft->xykell-lab | 156 | NO (established ~20ms later) | NO | n/a | n/a | NO | UNKNOWN |

Pipeline stage reached: binary frame -> decodeInbound() (no-session
branch) -> base64 record -> STOP. Decrypt, UTF-8/plaintext, JSON parse,
and application envelope stages were never reached for any real frame in
any session to date. The decrypt branch (`session.decrypt` ->
`toString('utf8')` -> `tryParseJson` -> `classify`) has run only against
synthetic tests, zero times against Minecraft post-establishment bytes.

No frame is classified as an event on the basis of being binary,
encrypted, 156B, or temporally correlated with chat/movement. No such
correlation data exists in the 9I window in any case.

## DECRYPTED PAYLOADS

None. Zero valid decrypted application JSON in the 9I window and zero in
all prior sessions. Per the stop rule, had any valid decrypted
application JSON appeared it would be reproduced here and analysis would
stop — there is nothing to reproduce. No adapter built, no fields
promoted.

## EVENT EVIDENCE

None. No PlayerMessage envelope, no PlayerTravelled envelope, no
`commandResponse`/`error`/`event` purpose tied to a subscription, no
subscription acknowledgement. Prior `ws:encrypt` + `commandResponse` key
frames prove key delivery only, not event delivery.

## GAME-STATE EVIDENCE

None. No chat text, no player position/travel, no world/block/entity
fields. Nothing was promoted into the Xykell runtime. `CONNECTED + 0
MESSAGES` (indeed, 9I: not even CONNECTED) proves transport-listener
liveness only, and only up to TCP-OK.

## SECURITY

- No production code touched (`git status` shows lab + research docs only;
  `app/`, `native/`, registry untouched). No encryption implementation
  modified.
- No private keys logged/persisted/committed; ephemeral P-384 identities
  from prior runs are gone with their processes. Public handshake material
  (ephemeral DER + salt) appears only in the two prior captures, as
  designed; `redactedKeys` empty throughout (scan-clean, no
  token/password/secret-like keys).
- Lab remains localhost-only (`HOST=127.0.0.1`, refuses `0.0.0.0`),
  observation-only + subscribe-only + documented-handshake-only. No Exec
  beyond the single `enableencryption` handshake command exists in the lab
  (test-enforced, 30/30 PASS).
- No credentials/tokens in terminal log (398-byte startup banner only) or
  in the empty 9I capture. No secrets in this report.
- Log-path deviation documented: the task's `/tmp/xykell-9i-terminal.log`
  is not creatable on Termux (`/tmp` is `drwxrwx--x shell:shell`; new-file
  creation returns Permission denied). The lab log was preserved at the
  Termux-writable equivalent
  `/data/data/com.termux/files/usr/tmp/xykell-9i-terminal.log`
  (`$PREFIX/tmp`, `$TMPDIR`). A stale 9I-env server holding the port with
  a broken `/tmp` tee was killed before the clean run; port conflict did
  not affect the result (TCP-OK proved the clean listener was bound).
- No commit, no push (none made).

## DECISION

"NO POST-ESTABLISHMENT TRAFFIC — EXPERIMENT INCONCLUSIVE"
