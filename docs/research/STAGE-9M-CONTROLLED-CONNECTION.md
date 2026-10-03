# Stage-9M controlled connection (diagnostic-only, Minecraft 1.26.52.3)

Objective: determine whether Minecraft 1.26.52.3 reaches the Stage-9L
lab (corrected standard unpadded Base64) and record the verbatim
game-side result. No source modified during this stage (lab and
production alike), no new encoding variant, no commit/push.

## 1. OBJECTIVE

Test the 9L corrected handshake (`enableencryption` with standard
unpadded Base64 key+salt) against real Minecraft: connection outcome,
exact game-side response, handshake verdict (`badsalt` / bad-key
present or absent), establishment, subscribes, and any inbound traffic
— without gameplay (no chat, no movement, no commands).

## 2. ENVIRONMENT

- Device: Android 16 (SDK 36), Termux 0.119.0-beta.3 (F-Droid),
  Node v24.18.0, aarch64 (per 9J device record; unchanged).
- Minecraft: 1.26.52.3, owned test world (cheats required for
  `/wsserver`; Admin permission level per documented command spec).
- Lab: `lab/bedrock-websocket` at Stage-9L code state (base64url →
  standard unpadded Base64, 38/38 tests), started as
  `SUBSCRIBE_ONLY=1 ENCRYPTED_SESSION=1 npm start` on 127.0.0.1:8765
  under `termux-wake-lock`; log at
  `$PREFIX/tmp/xykell-9m-terminal.log` (Termux-writable equivalent of
  `/tmp/`, which is not creatable here — established in 9I).

## 3. PRE-RUN TESTS

- `git status --short`: only intended Stage-9L lab/research work —
  tracked modifications under `lab/bedrock-websocket/` (prior-stage
  README/package/protocol/server) plus untracked `encryption.mjs`,
  `subscribe.mjs`, `test/encryption.test.mjs`,
  `test/subscribe.test.mjs`, and `docs/research/STAGE-9*.md`. No
  production Android/Kotlin/native entries. Confirmed before start.
- `npm test` in `lab/bedrock-websocket`: **38/38 PASS** (fail 0),
  matching the expected gate. Proceeded.

## 4. LISTENER VERIFICATION

Fresh listener started; new session file
`captures/capture-2026-10-03T08-11-40-660Z.jsonl`; banner +
`Waiting for Minecraft...` observed.

- `curl -i http://127.0.0.1:8765/` → `HTTP/1.1 426 Upgrade Required`
  (16-byte body). Healthy WebSocket-listener signature, not a Minecraft
  signal.
- `nc -zv 127.0.0.1 8765` → `nc: command not found` (not installed in
  this Termux; documented, not a listener fault). Fallback
  `bash /dev/tcp/127.0.0.1/8765` → **TCP-OK**.
- TCP availability is NOT Minecraft connectivity (stated as required).

## 5. MINECRAFT PROCEDURE

As specified: bare `/wsserver` first (drop any stale session), brief
wait, then exactly `/wsserver ws://127.0.0.1:8765`. URI unchanged. No
other WebSocket command. No chat, no movement, no `/execute`, no
packet activity of any kind. User confirmed the procedure done.

## 6. VERBATIM GAME RESPONSE

UNKNOWN. The user reported the game showed some text that matched
neither `commands.generic.encryption.badsalt` nor `Bad public key
given` ("Other text"), but no exact wording was supplied despite
follow-up, so nothing can be quoted. Per the rules this is recorded as
UNKNOWN, not paraphrased, not guessed. (Lab-side traffic below shows no
error frames on this connection, so whatever was displayed did not
arrive as a handshake rejection over this listener.)

## 7. TRANSPORT RESULT

Outcome **A — Connection proven**. Terminal log (1038 bytes, complete):

```
[CONNECTED] connection=conn-1
[HANDSHAKE] connection=conn-1 bytes=359
[HANDSHAKE] connection=conn-1 state=awaiting-key
[MESSAGE] connection=conn-1 bytes=275 classification=COMMAND_RESPONSE parsed=true
[MESSAGE] connection=conn-1 bytes=156 classification=UNKNOWN parsed=false
[HANDSHAKE] connection=conn-1 state=established
[SUBSCRIBE] connection=conn-1 bytes=172
[SUBSCRIBE] connection=conn-1 bytes=174
[SUBSCRIBED] connection=conn-1 events=PlayerMessage,PlayerTravelled
[MESSAGE] connection=conn-1 bytes=270 classification=OTHER parsed=true
[MESSAGE] connection=conn-1 bytes=228 classification=DECRYPTION_ERROR parsed=false encrypted=true
```

`[CONNECTED]`, `awaiting-key`, `established`, and `[SUBSCRIBED]` all
present — verified from the log, assumed from nothing.

## 8. HANDSHAKE RESULT

- `enableencryption` sent (359B, `cmd_len=204`, key 160 chars standard
  alphabet, salt 22 chars standard alphabet — structural metadata only,
  values not reproduced).
- Minecraft returned `ws:encrypt` (270B, client publicKey 160 chars)
  and `commandResponse` (275B, same client key) — key delivery, exactly
  the accepted shape, with the documented `hs-conn-1` request echo.
- **No `badsalt` observed** (no `commands.generic.encryption.badsalt`
  frame; compare 9J's 141B error frame — absent).
- **No `Bad public key given` observed** (no statusCode -2147352576
  frame; compare 9J's 209B error frame — absent).
- Establishment: **ENCRYPTED SESSION ESTABLISHMENT OBSERVED**
  (`state=established`, exactly once; single subscribe batch, guard
  held — the 275 duplicate did not re-trigger).
- Cryptographic correctness beyond the observable handshake is NOT
  claimed (see §10: the one post-establishment frame did not parse).

## 9. SUBSCRIPTION RESULT

Both subscribes sent exactly once post-establishment (172B
PlayerMessage + 174B PlayerTravelled, fresh UUIDs), followed by
`[SUBSCRIBED]`. No subscriptions added, none modified. No
acknowledgement of either subscription observed (no inbound frame
references them).

## 10. CAPTURE ANALYSIS

File: `captures/capture-2026-10-03T08-11-40-660Z.jsonl` — **2961 bytes,
7 lines** (inspected read-only; values below are structural metadata,
never key material):

| # | time (UTC) | dir | len | parsed | content |
|---|---|---|---|---|---|
| 0 | 08:14:49.343 | lab→MC | 359 | true | `commandRequest` `enableencryption`, key 160 chars std-alphabet, salt 22 chars std-alphabet |
| 1 | 08:14:49.661 | MC→lab | 270 | true | `ws:encrypt`, `publicKey` 160 chars, req `hs-conn-1` |
| 2 | 08:14:49.667 | MC→lab | 275 | true | `commandResponse`, `publicKey` 160 chars, req `hs-conn-1` |
| 3 | 08:14:49.668 | MC→lab | 156 | false | binary, base64-recorded, pre-establishment (25 ms before subscribes — decrypt never attempted, same as 9H) |
| 4 | 08:14:49.693 | lab→MC | 172 | true | `subscribe` PlayerMessage |
| 5 | 08:14:49.694 | lab→MC | 174 | true | `subscribe` PlayerTravelled |
| 6 | 08:15:00.561 | MC→lab | 228 | false | binary, base64-recorded, **≈11 s post-subscribes** |

- Inbound frames: 4 (270, 275, 156, 228). Outbound: 3 (handshake + 2
  subscribes).
- `enableencryption` sent: YES. `ws:encrypt` returned: YES (key, not
  error). `commandResponse` returned: YES (key, not error).
- `badsalt`: NOT PRESENT. `Bad public key given`: NOT PRESENT.
- Record #6 (228B) is the first-ever post-establishment inbound frame:
  terminal shows `encrypted=true`, `DECRYPTION_ERROR`, `parsed=false`,
  i.e. the decrypt branch ran once against real bytes and did not
  yield valid JSON. Whether it was decrypt-failed vs parse-failed is
  indistinguishable in the artifacts (the lab logs/captures the
  category but not the `error` detail — format gap, noted, not changed
  per mandate). It is NOT called an event; with no gameplay performed
  per §5 there is no action to correlate it with anyway. Retroactive
  decryption is impossible (ephemeral key gone with the stopped lab
  process), same standing rule as the 9H 156B frames.

## 11. ENCODING STATUS

- Stage 9L changed base64url → standard unpadded Base64: CONFIRMED ON
  THE WIRE (record #0 alphabets measured `std` for both fields).
- Stage 9L tests were 38/38 PASS: CONFIRMED (pre-run).
- 9M DID establish a Minecraft connection: YES (outcome A).
- `badsalt` observed: NO.
- `Bad public key given` observed: NO.
- Establishment occurred: YES (once).
- Subscriptions occurred: YES (both, once each).
- Event received: NO (nothing parsed as an envelope; #6 failed).
- Decryption proven: NO (one attempt, `DECRYPTION_ERROR`).
- Success is NOT claimed from absence of errors alone: the positive
  evidence is key delivery + `established` + subscribes + a
  post-establishment frame — and the one open negative is the
  undecrypted 228B frame plus the unknown game-side text.

## 12. PROVEN

- The corrected standard-unpadded handshake is accepted by Minecraft
  1.26.52.3 past validation: no `badsalt`, no bad-key error, client key
  delivered (270+275B), `state=established`, exactly one subscribe
  batch. The 9J rejection mode is gone against the 9L strings.
- Exactly-once establishment guard held against duplicate key frames
  with real timing (275 ignored after 270 claimed).
- The 156B pre-establishment binary pattern recurred (now 3/3 accepted
  sessions), still never decrypted (pre-session by 25 ms here).
- A post-establishment inbound frame exists for the first time (228B,
  +11 s): the decrypt path is now exercised against real bytes with an
  observed `DECRYPTION_ERROR` outcome.

## 13. NOT PROVEN

- Decryption of real traffic (0/1 parsed; the only post-establishment
  frame failed).
- Subscription acceptance by Minecraft (no ack for either event).
- Any event payload or game-state field (nothing parsed as an
  envelope).
- What the game displayed (UNKNOWN — see §6).
- Cryptographic correctness beyond observable handshake flow.

## 14. UNKNOWN

- Verbatim game-side text for the 9M `/wsserver` (reported as "other
  text", wording not supplied).
- Content and cause of the 228B `DECRYPTION_ERROR` frame
  (decrypt-failed vs parse-failed indistinguishable; no gameplay
  context by design; retroactive analysis impossible).
- Whether the 228B frame relates to the subscriptions, the session, or
  something independent.
- Why 9H's base64url keys passed validation while 9J's did not
  (carried over from 9K; 9M does not retroactively explain it).

## 15. CONCLUSION

TRANSPORT: OBSERVED (outcome A). PROTOCOL: handshake-accepted,
established, subscribed; first post-establishment frame captured but
undecrypted. ENCODING FIX: VALIDATED AGAINST MINECRAFT for the
handshake layer (`badsalt` and bad-key errors eliminated, establishment
achieved) — with the explicit boundary that payload decryption is 0/1
and the game-side verbatim remains unknown. This is NOT the hard-stop
condition (no new protocol error appeared), so no protocol change is
made in this stage.

## 16. NEXT MINIMAL STEP

Single diagnostic step, no code change: keep the identical 9L lab and
run one session in which (a) the exact game-side text after
`/wsserver` is transcribed within seconds (photo/transcription), and
(b) if `established` recurs, ordinary read-side actions (one chat,
move/stop) are performed with second-precision timestamps, so any
post-establishment frame can be tested against the decode pipeline and
its `DECRYPTION_ERROR` detail (decrypt- vs parse-failed — consider
logging the already-computed `error` field; that is observability, not
a protocol change) recorded per frame. If a valid decrypted envelope
appears: STOP and analyze envelopes (no adapter). If `DECRYPTION_ERROR`
recurs on action-correlated frames: analyze cipher/frame alignment
against mcwss before touching anything.

Security: nothing modified (lab, protocol, production all untouched —
verified §3); no private keys or secrets in this report or in the new
capture (`redactedKeys`-clean pattern; only structural lengths
reported); localhost-only, observation/subscription-only, no Exec, no
injection, no interception, no bypass; no commit/push.
