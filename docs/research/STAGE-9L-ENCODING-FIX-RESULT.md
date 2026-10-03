# Stage-9L encoding-fix result (lab only, research)

Scope honored: `lab/bedrock-websocket/` only. No production
Android/Kotlin/native code touched. Unchanged: P-384 curve, SPKI DER
generation, ECDH, SHA-256 derivation, CFB8, `wsencrypt` subprotocol,
handshake ordering, subscription protocol, security boundaries. Only
the textual encoding of the SPKI DER and salt in `enableencryption`
changed. No commit/push. No private keys in this report.

## Implementation change

`lab/bedrock-websocket/encryption.mjs`, `buildEnableEncryptionCommand`
— the single handshake-string construction site (test-enforced: the
only `commandLine` construction in the lab):

- Before: `Buffer.from(b).toString('base64url')` for both values
  (URL-safe `-`/`_`, unpadded). Header comment recorded `<b64url DER>`
  / `<b64url salt>` (the Stage-9F choice Stage-9K identified as the
  deviation).
- After: `Buffer.from(b).toString('base64').replace(/=+$/, '')` for
  both values (standard `+`/`/` alphabet, padding stripped — Node
  equivalent of Go `base64.RawStdEncoding` used by mcwss). Header
  comment now documents standard-unpadded as the wire form and notes
  base64url is rejected by Minecraft.
- Command structure unchanged:
  `enableencryption "<std-b64-key>" "<std-b64-salt>"` (verified
  `cmd_len=204`, framing identical to all prior sessions).
- No other file required changes: `server.mjs` decodes the client's
  key with `Buffer.from(s, 'base64')` (untouched, out of scope);
  capture-side `base64` uses in `decodeInbound` are opaque-byte
  recording, not handshake encoding (untouched).

## Exact encoding before/after

| Value | Before (9H–9K) | After (9L) |
|---|---|---|
| P-384 SPKI DER | base64url, 160 chars, `-`/`_` possible | standard unpadded, 160 chars, `+`/`/` possible, `-_`/`=` never |
| 16-byte salt | base64url, 22 chars, `-`/`_` possible | standard unpadded, 22 chars, `+`/`/` possible, `-_`/`=` never |
| Lengths | 160 / 22 | 160 / 22 (unchanged — same sizes under both alphabets) |
| Crypto bytes | P-384 SPKI 120 B, salt 16 B | identical (encoding-only change) |

Live verification (generated identity, public material only — key text
not reproduced): `key_strlen=160 std=true nopad=true decoded=120`;
`salt_strlen=22 std=true nopad=true decoded=16`; `cmd_len=204`.

## Test results

Full lab suite: **38/38 PASS** (`npm test`: protocol + subscribe +
encryption, incl. all pre-existing tests — requirement 10 satisfied, no
existing test broken).

New deterministic tests in `test/encryption.test.mjs` (fixed vectors
`FB FF FE…` chosen so standard-base64 output MUST contain `+`
sextet 62 and `/` sextet 63; no randomness):

1. Key uses standard base64 — PASS (`+` and `/` present, full-match
   `[A-Za-z0-9+/]+`).
2. Salt uses standard base64 — PASS (same assertions on salt field).
3. Padding removed — PASS (salt exactly 22 chars, `=` absent; 3-byte
   vector encodes to exactly `+//+`).
4. `+` preserved — PASS (asserted present in both fields).
5. `/` preserved — PASS (asserted present in both fields).
6. `-`/`_` never emitted — PASS (key+salt fields, plus a 0..255 sweep
   through the builder).
7. 120-byte SPKI-length vector encodes to exactly 160 chars — PASS.
8. 16-byte salt encodes to exactly 22 chars — PASS.
9. Standard-unpadded decode reproduces exact original bytes — PASS
   (both fields, `deepEqual`).
10. Existing encrypted-session tests still pass — PASS (38/38).

One pre-existing test was updated with stated reason: the
`enableencryption command format` regex allowed only `[A-Za-z0-9_-]`;
it now requires `[A-Za-z0-9+/]` because the documented wire alphabet
changed per 9K evidence (the old assertion encoded the bug, not the
contract). Input-validation assertions (salt length, empty key)
unchanged.

## Minecraft connection result

Lab started as specified (with the Termux-writable log path documented
in 9I/9J: `$PREFIX/tmp/xykell-9l-terminal.log`, since `/tmp/` is not
creatable on this Termux): `termux-wake-lock`,
`SUBSCRIBE_ONLY=1 ENCRYPTED_SESSION=1 npm start`, banner observed,
session file `captures/capture-2026-10-03T08-06-59-172Z.jsonl` created.
Listener verified alive during the window (`TCP-OK` via
`/dev/tcp/127.0.0.1/8765`; server process present).

After the user-confirmed chat/move/wait window: **no connection
arrived**. Terminal log (398 bytes) contains only the startup banner +
`Waiting for Minecraft...` — zero `[CONNECTED]`, zero `[HANDSHAKE]`,
zero `[SUBSCRIBE]`/`[SUBSCRIBED]`, zero `[MESSAGE]`. Latest capture:
**0 bytes / 0 lines**. Prior captures unchanged (05-25/05-32 at 2557 B,
07-41 0 B, 07-55 1296 B). Lab stopped afterwards.

## Exact handshake result

No handshake occurred in the 9L window — there is no handshake result
to quote. Minecraft's exact on-screen response to
`/wsserver ws://127.0.0.1:8765` is UNKNOWN (user could not report what
the game displayed; follow-up answer: "Unknown response"). No
`ws:encrypt` frame, no `commandResponse`, no error text was captured on
either side.

## Whether `badsalt` disappeared

UNPROVEN either way. No handshake reached validation, so Minecraft never
had the opportunity to accept or reject the corrected salt. The 9J
`commands.generic.encryption.badsalt` stands as the last observed salt
verdict (against the old base64url form). Absence of the error here is
absence of a connection, NOT evidence of acceptance — no success is
claimed from it.

## Whether `Bad public key given` disappeared

UNPROVEN either way, same reason. The corrected key (standard alphabet,
160 chars, decodes to the same valid 120-byte P-384 SPKI the 9K analysis
verified structurally) was never presented to Minecraft over the wire
in this window. The 9J bad-key message stands as the last observed key
verdict (against the old form).

## Whether encrypted session establishment was actually proven

NO. Establishment requires the full chain (key delivery → derive →
`state=established` → subscribes). None of it happened in the 9L
window: no `[CONNECTED]`, no `awaiting-key`→`established` transition,
no `[SUBSCRIBED]`. The corrected encoding's acceptance is entirely
untested against real Minecraft bytes.

## Whether subscriptions were sent

NO. Subscribes emit only post-establishment; establishment never
occurred, so neither PlayerMessage nor PlayerTravelled frames were sent
in the 9L window.

## Whether any event payload was observed

NO. Zero inbound frames, zero envelopes, zero game-state fields. Same
as 9I.

## Whether decryption was proven

NO. `decodeInbound`'s decrypt branch ran zero times against Minecraft
traffic (as in every prior stage — synthetic tests only). No
decrypted JSON, no `CHAT`/`PLAYER` classification from real bytes.

## Remaining UNKNOWNs

- What Minecraft displayed for the 9L `/wsserver` attempt (verbatim
  game-side text is the blocking missing datum, as in 9I).
- Whether the corrected standard-base64 strings are accepted
  (unproven — the lab fix is verified mechanically only).
- Why no TCP connection arrived despite a live listener (same open
  variables as 9J: command echo/typo, cheats/Admin/world state,
  stale-session without empty-string disconnect, VPN/DNS/proxy,
  Termux background-suspend while Minecraft is foreground despite
  `termux-wake-lock`, or the attempt never actually executed).
- Whether `badsalt` and bad-key errors are coupled or independent on
  the Minecraft side (carried over from 9K).
- Everything downstream: establishment → subscribes → events →
  decryption → payloads.

## Next minimal step

Do NOT change encoding or crypto again (hard-stop rule respected: no
rejection of the corrected form was observed, so no new encoding may be
guessed). The next step is a single controlled connectivity run that
captures the one missing datum, changing nothing else:

1. Foreground Termux, `termux-wake-lock`, start the lab exactly as in
   this stage; proceed only after `Waiting for Minecraft...`.
2. In the owned 1.26.52.3 test world (cheats ON): empty-string
   `/wsserver` first, then exactly `/wsserver ws://127.0.0.1:8765`,
   and transcribe the VERBATIM on-screen result within 60 s
   (success line, or refused/timeout/invalid-URL text, or precisely
   "no visible result"), plus VPN/Private-DNS state.
3. If `[CONNECTED]` appears, continue ONLY on establishment +
   subscribes, then chat/move/wait; otherwise stop and report the
   game-side text — that text alone is the result that decides whether
   the 9L fix is accepted or the hard stop triggers.

Security: no private keys logged/printed/persisted (verification used
lengths/alphabet flags only); no secrets in captures
(`redactedKeys`-clean pattern preserved; 9L capture empty); lab stayed
localhost-only, observation/subscription-only, single documented
handshake command; no Exec, injection, interception, or boundary bypass;
no commit/push.
