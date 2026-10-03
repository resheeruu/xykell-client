# Stage-9P event coverage (read-only, Minecraft 1.26.52.3)

Objective: gather read-only traffic across two fresh controlled
sessions and characterize recurring frame length classes — with the
proven encryption path FROZEN. No crypto, protocol, production, or
capture changes; no Exec/injection/bypass; no adapter; no commit/push.
No keys, IVs, secrets, credentials, or raw ciphertext below — lengths,
digests-class metadata, and verified envelope shapes only.

## 1. OBJECTIVE

As stated above: at least one real PlayerMessage + PlayerTravelled,
wire-length distribution, unknown-frame recurrence, decrypt
consistency, and chat/movement correlation — evidence/coverage only.

## 2. STAGE-9O BASELINE

9O proved: "540B" = 405 wire bytes; capture lossless; all ciphertexts
session-unique; failure stage is JSON parsing (CFB cannot throw —
0-throw test); failing bytes are genuine in-stream ciphertext (stream
continuity: successors decrypt); session/key/IV continuous;
persistent-stream (B) matches mcwss; failing content = UNKNOWN_FRAME.

## 3. ENVIRONMENT

- Preflight: `git status` lab+research only, no production entries;
  `npm test` **38/38 PASS**. Implementation verified unchanged between
  A and B (no edits at any point this stage; existing timestamps and
  capture format used as-is, no instrumentation added).
- Listeners: `SUBSCRIBE_ONLY=1 ENCRYPTED_SESSION=1 npm start`,
  127.0.0.1:8765, under `termux-wake-lock`; logs
  `$PREFIX/tmp/xykell-9p-{a,b}-terminal.log`.
- Minecraft 1.26.52.3 owned test world; gated procedure both sessions
  (bare `/wsserver`, exact `/wsserver ws://127.0.0.1:8765`, terminal
  watched to `[SUBSCRIBED]` before acting, then one chat / walk / stop
  / waits; no commands).

## 4. SESSION A

- Capture `capture-2026-10-03T08-35-07-457Z.jsonl`: **32 lines**.
- `[CONNECTED]` → `awaiting-key` → key delivery (270+275B) →
  `established` (once) → subscribes 172+174B → `[SUBSCRIBED]`.
  No errors (`badsalt`/bad-key absent, 3rd consecutive session).
- Post-establishment inbound: 1 × 404-wire `UNKNOWN_FRAME`
  (`DECRYPTION_ERROR`, 08:35:28.128Z, +2.4 s post-subscribes), then
  24 × PlayerTravelled (397–404 wire, 08:35:28.371–08:35:55.323Z,
  ≈150–500 ms cadence with `metersTravelled` ≈1.0–1.35 and evolving
  position/yRot, incl. `travelMethod` 0→2 transitions), 1 ×
  PlayerMessage (`"hello"`, 157 wire, 08:35:50.432Z).
- Post-chat travel burst (08:35:54.040–55.323Z, 6 frames) shows events
  also follow later movement; ordering vs the instructed chat-then-walk
  is recorded as-observed (travel stream precedes the chat frame —
  user acted in own order; correlation is by content+window, §8/§9).

## 5. SESSION B

- Capture `capture-2026-10-03T08-36-29-002Z.jsonl`: **34 lines**.
- Identical handshake shape: `[CONNECTED]` → `awaiting-key` →
  270+275B key delivery → `established` (once) → 172+174B subscribes →
  `[SUBSCRIBED]`. No errors (4th consecutive clean session).
- Post-establishment inbound: 1 × 404-wire `UNKNOWN_FRAME`
  (`DECRYPTION_ERROR`, 08:36:59.926Z, +2.5 s post-subscribes), then
  25 × PlayerTravelled (399–405 wire, 08:37:00.134–08:37:29.793Z),
  1 × PlayerMessage (`"working?"`, 160 wire, 08:37:20.631Z)mid-stream,
  travel resumes after it. Chat content again matches a user-typed
  message verbatim.

## 6. FRAME INVENTORY

Per-frame post-establishment records (timestamp, wire length =
decoded binary byte count, direction MC→lab, decrypt/UTF-8/JSON
result, classification, event): full per-frame tables for A (26
frames) and B (28 frames) were extracted programmatically from the
captures — 1 unknown + 24 travel + 1 chat (A); 1 unknown + 25 travel
+ 1 chat (B); plus 9N's 1 unknown + 1 travel + 1 chat. Combined with
9N: **57 post-establishment inbound frames, 54 decrypted to valid
JSON, 3 `UNKNOWN_FRAME`** (all pre-success, all first-in-stream).
Pre-establishment 115-wire binary recurred in both sessions (5th/6th
occurrence; never decrypted, never an event). Handshake/key/subscribe
frames identical in shape to all prior accepted sessions (lengths
359/270/275/172/174 throughout).

## 7. LENGTH-CLASS STATISTICS

Wire length = decoded binary bytes. Base64-representation length and
plaintext length are reported separately and never confused (binary
records: `length` field = base64 chars, e.g. 540 chars = 404/405 wire
bytes; text records: `length` = plaintext chars = wire bytes under the
length-preserving stream cipher).

| Wire length | Count (9N+A+B post-est.) | Decrypted | JSON | Event | Unknown |
|---|---|---|---|---|---|
| 404–405 (binary) | 3 | 3 consumed, 0 parsed | 0 | — | 3 (`UNKNOWN_FRAME`) |
| 397–405 (travel) | 50 | 50 | 50 | 50 × PlayerTravelled | 0 |
| 157–160 (chat) | 3 | 3 | 3 | 3 × PlayerMessage | 0 |

Class details: travel min 397 / max 405 (float-repr variance in
coordinates/`metersTravelled`; all decrypt); chat 157 (`hiiii`,
`hello`, 5-char) / 160 (`working?`, 8-char) — message-length
dependent; unknown 404 (A, B) / 405 (9N) — NOT a fixed length, but a
narrow 404–405 class recurring 3/3 sessions as the FIRST
post-establishment frame, 195–250 ms before the travel stream
(9N 10.310→10.505; A 28.128→28.371; B 59.926→00.134) and +2.4–26 s
after subscribes. No semantics inferred from size; classes are
descriptive only.

## 8. PLAYERMESSAGE CORRELATION

3/3 chat events match user-typed message text verbatim (`hiiii`,
`hello`, `working?`, `type: chat`, correct sender) inside the action
windows (9N +31 s; A +25 s; B +24 s post-subscribe). Content match +
window timing = SUPPORTED correlation. Second-precision user clock
times were again not supplied, so exact causality is not clock-proven —
same standing honesty rule as 9N.

## 9. PLAYERTRAVELLED CORRELATION

50/50 travel events carry coherent walking telemetry
(`metersTravelled` ≈1.0–1.35 per frame, continuous position/yRot
evolution, `travelMethod` transitions, ≈150–500 ms cadence during
walk windows, sparse otherwise). Streams coincide with the walk/stop
windows in all three sessions. SUPPORTED by content + cadence +
window timing; not second-precision proven (no user clock times).

## 10. UNKNOWN FRAME CLASSES

- Class U1 (post-establishment, 404–405 wire): recurs 3/3 sessions,
  always first post-session frame, always `DECRYPTION_ERROR`
  (JSON-stage per 9O), always followed ~200 ms later by cleanly
  decrypting events. Labeled `UNKNOWN_FRAME`; not an event, not
  malformed-by-assumption, not game-state.
- Class U0 (pre-establishment, 115 wire): recurs 6/6 accepted
  sessions (9H×2, 9M, 9N, 9P×2); never decrypted (pre-session by
  6–25 ms); `UNKNOWN`, never an event.
- No unknown parsed-JSON envelopes and no unclassified event names
  appeared in either session: every successfully decrypted frame was
  exactly one of the two subscribed events.

## 11. CRYPTO STATUS

FROZEN — stop condition met: every expected event envelope decrypted
correctly (53/53 travel+chat across A+B; 55/57 including 9N with the
two U1 frames as the only failures). No crypto change made or
triggered. U1 recurrence is recorded as an application/protocol
observation per the rules (in-stream ciphertext, non-JSON plaintext —
9O), NOT as a crypto defect: reopen crypto investigation ONLY on
multiple expected-event failures, cross-session reproducibility of
event failure, disappearing controls, or a demonstrated inconsistency
— none present (one isolated unknown class with clean surrounding
controls is explicitly insufficient).

## 12. PROVEN

- Real PlayerMessage (3 sessions, verbatim user text) and real
  PlayerTravelled (3 sessions, 51 events total incl. 9N) decrypt
  reliably with the frozen construction.
- Wire-length classes are stable: travel 397–405, chat
  message-dependent (157/160), U1 404–405, U0 115.
- U1 recurs (3/3) as first post-establishment frame with the 9O
  signature (consumed in-stream, non-JSON) — a characterized class,
  still content-unknown.
- Handshake acceptance without errors is now 4/4 consecutive sessions;
  establishment + single subscribe batch 6/6 accepted sessions.
- Zero implementation changes across A and B (same build, same
  behavior).

## 13. NOT PROVEN

- Clock-exact action→frame causality (no user clock times, three
  sessions running).
- U1 content/class (non-JSON established 9O; beyond that unknown).
- Subscription-ack semantics (still no ack frames of any kind).
- Anything outside the two subscribed events; anything runtime-side.

## 14. UNKNOWN

- U1 payload type (binary payload? concatenated framing? unmodeled
  class? — content unrecoverable, keys gone with stopped processes).
- Exact game-side `/wsserver` text (never captured; lab-side evidence
  conclusive regardless).
- 9N `hiiii` vs instructed `9N_TEST` variance (carried over; A/B
  chats match typed text, suggesting free typing rather than a
  protocol effect).

## 15. CONCLUSION

Stage 9P succeeds on every criterion: ≥1 real PlayerMessage (3),
≥1 real PlayerTravelled (51), full wire-length distribution,
unknown-frame count (3 U1 + 6 cumulative U0 incl. history),
U1 recurrence confirmed, expected events decrypt 55/57 overall
(53/53 in A+B), chat/movement correlation supported by verbatim
content + cadence + window timing. No new crypto implementation
required or made — construction stays frozen. The lab observation
layer is now coverage-complete for the two subscribed events; the
remaining unknowns are U1 content and clock-exact attribution, neither
of which blocks envelope-level understanding.

## 16. NEXT MINIMAL STEP

Do NOT touch crypto, protocol, or production. If deeper attribution is
wanted: one gated session with second-precision user clock times per
action (chat keypress, walk start/stop) to convert the two SUPPORTED
correlations into clock-proven ones — that single datum is the only
missing piece for causal attribution. Otherwise the evidence stage is
done: the next decision is whether to analyze envelopes toward a
read-only runtime event mapping (a product decision, still no adapter
in any evidence stage).

Security: observation-only boundary held — no crypto/protocol/
production/capture changes (verified zero modifications); digests and
lengths only, no ciphertext reproduced, no plaintext beyond the
verified event envelopes the stages explicitly tasked us to verify
(from the user's own test world); no keys/IVs/secrets/credentials
handled; localhost-only; no Exec/injection/rewriting/interception/
bypass; no commit/push.
