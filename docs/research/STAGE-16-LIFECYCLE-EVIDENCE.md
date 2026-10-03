# Stage-16 lifecycle evidence (lab-only, Minecraft 1.26.52.3)

Question: can the proven localhost encrypted observation session
survive realistic Android foreground/background transitions while
Minecraft is the foreground app? Measured, not theorized. No code
changed (zero modifications — existing logs sufficed); no production
work of any kind; no commit/push.

## Environment

- Android 16 (SDK 36), Termux 0.119.0-beta.3 (F-Droid), Node v24.18.0.
- Minecraft Bedrock 1.26.52.3, owned test world, cheats on.
- Server: `SUBSCRIBE_ONLY=1 ENCRYPTED_SESSION=1 npm start`,
  127.0.0.1:8765, observation-only; `termux-wake-lock` HELD for the
  entire session (all experiments A–E).
- Artifacts: `$PREFIX/tmp/xykell-16-terminal.log` (10766 bytes) +
  `captures/capture-2026-10-03T11-27-31-411Z.jsonl` (119 lines).
  Single continuous server process; lab stopped after E.

## Baseline (Exp A)

`[CONNECTED]` conn-1 → `awaiting-key` → key delivery (270+275B) →
`established` (once) → subscribes 172+174B → `[SUBSCRIBED]`. No
errors (5th consecutive clean session). U0 (115-wire) + U1 (404-wire,
`DECRYPTION_ERROR`) recurred with known signatures.

## Foreground (Exp B)

With Minecraft foreground: 3 chats (`hii`, `hello`, `working?`) +
~45 travel events over 12:13:42–12:15:19Z, all decrypted to verified
envelopes. Continued observation under normal foreground operation:
PROVEN for this session.

## Background transition (Exp C)

Minecraft left via Home/app-switcher (~10–20 s, both processes kept
alive), then returned; no game input during the gap. Observed:
server process alive throughout; NO `[DISCONNECTED]`; NO second
`[CONNECTED]`; NO error lines; capture shows no frames inside the
gap (12:15:19 → 12:17:38Z, ≈2.3 min including return + D setup —
user transition clock times not supplied, so gap edges are
server-timestamped and transition timing is marked approximate).
Silence during backgrounding is the expected observation (no input
to report), not a dropout — the socket stayed open.

## Return to Minecraft (Exp D)

First post-return traffic 12:17:38Z on the SAME conn-1: chats
`hiiiiiu`, `hello` + resumed travel stream, all decrypting cleanly
with the pre-gap session (keystream continuity → encryption state
intact; no resubscribe sent or needed → subscription state intact).
Observations resume normally: PROVEN.

## Wake-lock comparison (Exp E)

Second Home-away (~25 s gap 12:17:40 → 12:18:05Z) WITH wake-lock
held, then return + chats (`wassup`, `testing hello`, `mic test`) +
travel — all clean on conn-1. E repeats C's outcome under the same
condition (repeatability with lock: PROVEN). A without-wake-lock
control was NOT run (device-sleep risk, out of scope), so survival
cannot be attributed to the lock: WITHOUT-LOCK behavior NOT TESTED.

## Evidence table

| Property | Status |
|---|---|
| TCP connection survives | PROVEN (one conn-1 across ≈4.5 min + two background gaps; no close/reconnect) |
| Encryption survives | PROVEN (post-gap frames decrypt to valid JSON on the same session) |
| Subscription survives | PROVEN (events flow post-gap with subscribes sent exactly once) |
| Events continue | PROVEN (D: 2 chats + travel; E: 3 chats + travel; 104 travel + 8 chats total) |
| Lab process survives | PROVEN (PID alive 51+ min, logging throughout; stopped deliberately at end) |
| Minecraft remains connected | PROVEN (no client close; traffic resumes on same connection; user reported normal resume) |
| Reconnect behavior understood | UNKNOWN (no drop occurred, so reconnect was never exercised — only that none was needed) |

The seven states were never collapsed: each row has its own positive
evidence except reconnect-on-drop, which is UNKNOWN (not failure).

## Validation

- Host suite **27/27 PASS**, lab suite **58/58 PASS** (preflight;
  zero code changes this stage, so baselines stand).
- No logging modification was necessary (existing terminal + capture
  records supplied every required field).

## Security

Lab-only lifecycle observation: no transport/crypto/protocol/
production change (verified zero modifications); no permissions, JNI,
UI, registry, provider, or networking work; no Exec/injection/
interception/bypass; localhost-only; no commit/push.

## Decision

**LIFECYCLE-SURVIVAL-PROVEN**

With the stated condition (Termux wake-lock held — the standard lab
posture since 9M): TCP, encryption, subscriptions, event flow, lab
process, and Minecraft-side connection all survived two background
transitions and resumed normally. The only UNKNOWN is reconnect-after-
drop (never triggered) plus the without-wake-lock control (never run);
neither weakens the positive survivals. This resolves the Stage-15
lifecycle UNKNOWN for the wake-lock-held posture only — it is NOT a
production-integration decision (no production surface was touched or
proposed).
