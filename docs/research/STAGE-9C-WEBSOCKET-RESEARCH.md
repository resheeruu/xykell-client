# Stage-9C WebSocket research (why connect succeeds, messages don't)

## 1. Objective
Explain two local observations: `/wsserver ws://127.0.0.1:8765` connects
(terminal `[CONNECTED]`, twice) yet zero application messages arrive
(two 0-byte captures) despite gameplay actions.

## 2. Exact Minecraft version
com.mojang.minecraftpe 1.26.52.3 (972605203), minSdk 32 / target 36,
arm64 split (re-verified this stage; updated since Stage 3's 1.26.45.1).

## 3. Local experiment evidence
- Capture 03-41-03: 0 bytes, terminal CONNECTED observed.
- Capture 03-58-29: 0 bytes, terminal CONNECTED observed, gameplay
  actions performed, no MESSAGE lines, grep finds nothing.
- Classification: TRANSPORT CONNECTION — PROVEN BY LOCAL EVIDENCE
  (twice). APPLICATION MESSAGE EXCHANGE — NOT OBSERVED. GAME-STATE
  OBSERVATION — NOT OBSERVED.

## 4. What [CONNECTED] proves / does not prove
Proves: TCP + WebSocket upgrade + MC client dial-out to our URI works on
retail Android localhost. Does NOT prove: any application payload, any
subscription, any game-state channel, or that MC will speak first.

## 5–7. Public documentation findings
- Official (Microsoft Learn, `/wsserver` command reference):
  "Attempts to connect to the websocket server on the provided URL."
  Admin permission, cheats required, alias `/connect`, empty string
  disconnects. No message semantics documented officially. DOCUMENTED.
  (https://learn.microsoft.com/en-us/minecraft/creator/commands/commands/wsserver)
- Community implementation (Sandertv/mcwss, MIT, reputable Bedrock
  protocol author; treated as strong community documentation, NOT
  official): the channel is SUBSCRIBE-driven — "the server can choose to
  listen for certain events"; every `OnXxx` handler subscribes; `Exec`
  sends command requests; connection persists across world leave;
  optional encrypted subprotocol `com.microsoft.minecraft.wsencrypt`.
  DOCUMENTED (community) — (https://pkg.go.dev/github.com/sandertv/mcwss).
- Scripting API `@minecraft/server-net` WebSocketClient (experimental):
  game-side-initiated client with send/close — corroborates the
  game-dials-out shape. DOCUMENTED (official, experimental scope).

## 8. Lifecycle (best available model)
CONNECT (observed) → server SUBSCRIBES to named events / issues command
requests (documented-community; our lab never does) → Minecraft emits
subscribed events + command responses → UNSUBSCRIBE/close. Our lab stops
after step 1 BY DESIGN (observation-only, no reverse direction), so
silence is the predicted outcome, not an anomaly.

## 9. Message model (community-documented, not locally verified)
Envelope `{header, body}` with `messagePurpose` (`subscribe`,
`commandRequest`, `commandResponse`, `event`, `error`) and `eventName`.
Our lab classifier already keys on exactly this shape; no local payload
has ever exercised it beyond synthetic tests.

## 10. Game-state capabilities (documented-community, locally unobserved)
Event catalog exists in principle (PlayerTravelled/Transform, PlayerMessage,
BlockBroken/Placed, item/mob/world lifecycle, SlashCommandExecuted...),
each requiring explicit subscription; Exec enables command
request/response. No position/health/inventory/world-state claim is LOCALLY
PROVEN. Status: DOCUMENTED-CAPABLE, LOCALLY UNOBSERVED.

## 11. Android/client limitations
None blocking transport found: localhost dial-out works on retail Android;
no documented Android-specific `/wsserver` restriction located. Encrypted-
websocket MC setting exists; our upgrade succeeded without negotiating it,
so it did not block transport here. Whether per-event delivery needs it
is UNKNOWN.

## 12. Lab implementation analysis
server.mjs: accepts upgrade, handles text (UTF-8) + binary (base64),
parses JSON separately, classifies conservatively, persists redacted
JSONL, NEVER sends. Therefore an empty capture under a valid connection
is the CORRECT output of this lab, not a malfunction. TRANSPORT vs
APPLICATION vs GAME-STATE remain three distinct evidence levels, and only
the first is locally proven.

## 13. Documented vs inferred
DOCUMENTED: connect semantics + cheats requirement (official);
subscribe-driven events/commands + subprotocol + persistence behavior
(community). REASONABLE INFERENCE: our silence = missing subscription
(explains 100% of observations, contradicts none). UNKNOWN: exact
subscribe frame bytes on 1.26.52.3; encryption interplay per event.

## 14. Security considerations
Research introduces nothing new: no auth touched (XAL flow untouched),
no credentials (none observed), no injection/packets/servers. A future
subscribing lab must keep the no-command-execution rule unless the user
explicitly authorizes command tests — subscription is read-side, Exec is
write-side, and only the read-side is currently justified.

## 15. Decision gate: PATH B
Command/event channel, not passive game state. What can legitimately be
observed (after a future subscribing lab): opted-in event stream +
command responses. Do NOT implement yet.

## 16. Recommended next experiment
Extend the lab with a SUBSCRIBE-ONLY mode (send `subscribe` frames for a
small documented event set, e.g. PlayerMessage + PlayerTravelled; still no
Exec/commands), re-run the manual test, and check whether MESSAGE lines
appear. That single experiment separates "subscription missing" (PROVEN
if traffic appears) from deeper version/encryption limits.
