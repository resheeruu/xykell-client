# Stage-9J connection diagnosis (Minecraft 1.26.52.3, read-only)

Goal: determine why the Stage-9I `/wsserver ws://127.0.0.1:8765`
connection did not reach the listener. No cause assumed.
No changes to encryption, production code, protocol handling, or the
capture parser. No commit/push.

## CURRENT EVIDENCE

- Stage 9H PROVEN (re-verified in full, 6 records each, fresh keys):
  `capture-2026-10-03T05-25-11-246Z.jsonl` and
  `capture-2026-10-03T05-32-04-888Z.jsonl` (2557 bytes each) show a real
  Minecraft connection, a proven encrypted handshake (out 359B
  enableencryption, in 270B `ws:encrypt` key, in 275B `commandResponse`
  key), one 156B encrypted frame arriving BEFORE establishment (decrypt
  never attempted per `decodeInbound` no-session branch), then one
  subscribe batch (172B PlayerMessage + 174B PlayerTravelled).
- Stage 9I PROVEN: clean server `SUBSCRIBE_ONLY=1 ENCRYPTED_SESSION=1
  npm start` bound 127.0.0.1:8765 (30/30 tests PASS); new session file
  `capture-2026-10-03T07-41-32-208Z.jsonl` stayed 0 bytes / 0 lines;
  terminal log (398 bytes) contains only the startup banner and `Waiting
  for Minecraft...` — no `[CONNECTED]`, no `[HANDSHAKE]`, no
  `[SUBSCRIBE]`, no `[MESSAGE]`. Therefore no post-establishment traffic
  exists. Decision stands:
  "NO POST-ESTABLISHMENT TRAFFIC — EXPERIMENT INCONCLUSIVE".
- Stage 9J diagnostics (this report, synthetic only, file since removed):
  while a same-config listener ran, `curl -v http://127.0.0.1:8765/`
  returned `HTTP/1.1 426 Upgrade Required` (16-byte body); `node:net`
  `net.connect(8765,'127.0.0.1')` logged `TCP CONNECTED`; `node:ws`
  client logged `WS OPEN OK` / `WS CLOSED`; server logged `[CONNECTED]
  conn-1`, `[HANDSHAKE] bytes=359 state=awaiting-key`, `[DISCONNECTED]`.
  The 1-record synthetic capture was deleted afterwards to restore the
  9I evidence set; current `captures/` holds only the two 2557-byte 9H
  files plus the 0-byte 9I file. No Minecraft data was involved.
- Missing evidence (the actual gap): what Minecraft itself reported for
  the 9I `/wsserver` attempt — success, refused, timeout, invalid URL,
  permission/network failure, or no visible result — was never captured.
  Silence was not recorded as success; it is simply UNKNOWN.

## LISTENER ANALYSIS

Source: `lab/bedrock-websocket/server.mjs` (inspected, not changed).

- Bind: `HOST = process.env.BEDROCK_WS_HOST ?? '127.0.0.1'`,
  `PORT = Number(process.env.BEDROCK_WS_PORT ?? '8765')`.
  9I ran defaults: 127.0.0.1:8765. `HOST === '0.0.0.0'` is refused
  (exit 2), so the lab is localhost-only by construction.
- WebSocket: `new WebSocketServer({ host: HOST, port: PORT,
  maxPayload: 256*1024, handleProtocols: ENCRYPTED_SESSION ? () =>
  'com.microsoft.minecraft.wsencrypt' : undefined })`.
- Path handling: no `path` option is set, so the `ws` server accepts the
  upgrade on any path. `ws://127.0.0.1:8765` (no path) connects
  directly; a trailing path would also be accepted. No path-based reject
  exists.
- Pre-upgrade rejects: none for WebSocket-shaped requests. A plain HTTP
  `GET /` is answered `426 Upgrade Required` (observed via curl), which
  is the correct healthy-listener signature, not a rejection of
  Minecraft. Nothing filters by User-Agent, Origin, or client identity
  before the upgrade.
- Post-upgrade behavior cannot explain a missing `[CONNECTED]`: the
  `connection` handler logs first, before any handshake/subscribe logic.
  A 9I log with no `[CONNECTED]` means no TCP+upgrade completed, not a
  post-connect drop.
- Verdict: the listener accepts `ws://127.0.0.1:8765` from any same-device
  client that can open TCP to device loopback. The 9J synthetic WS-OPEN
  proves the accept path works from the Termux context.

## MINECRAFT CONNECTION BEHAVIOR

Authoritative command reference (not guessed):

- Microsoft Learn (`learn.microsoft.com/.../commands/wsserver`):
  `/wsserver` (alias `/connect`), permission level Admin, requires
  cheats, one argument `serverUri` — "A URI of the server to connect to.
  Or use an empty string to disconnect." `ws://127.0.0.1:8765` is a
  well-formed value under this syntax.
- Minecraft Wiki (`minecraft.wiki/w/Commands/wsserver`): exclusive to
  Bedrock/Education; "Connect or disconnect to specified WebSocket
  server"; `serverUri` is a greedy raw-text argument; empty string
  disconnects. Used by Education Code Connection / Classroom Mode.
- Community server implementations converge on the same usage:
  `railsbob/minecraft-wss` documents `/connect ws://localhost:25565`
  with cheats enabled; `mcwss` npm docs show `/wsserver ws://localhost:1`
  and `api.start(port,'127.0.0.1')` reaching "any host that you own, or
  just localhost".
- Failure reporting: neither Microsoft Learn nor the Wiki documents the
  exact on-screen text for refused/timeout/invalid-URL/permission cases
  of `/wsserver` on Bedrock Android. The Gaming-SE Code-Connection thread
  shows the client does surface failures ("can't connect to server") on
  other platforms, and notes the Windows-UWP loopback exemption as a
  platform-specific cause there — which does NOT apply to Android (no
  equivalent documented block; and 9H proves this device connected).
- Consequence for 9I: the only authoritative next datum is the literal
  on-screen result of the `/wsserver` command in the owned 1.26.52.3
  world (exact text or precisely "no visible result", plus cheats/Admin
  state and whether a prior session was disconnected first with an empty
  `/wsserver`). That datum was not captured, so the client-side cause is
  UNKNOWN. The next manual run MUST record it verbatim.

## ANDROID/TERMUX NETWORKING

Device (observed): Android 16 (SDK 36), Termux 0.119.0-beta.3 (F-Droid,
targetSdk 28), Node v24.18.0, aarch64. Minecraft 1.26.52.3 (target world
version per prior stages).

- Loopback is device-shared, not per-app: LWN ("Covert web-to-app
  tracking via localhost on Android", 2025-06-11) states Android allows
  any installed app with INTERNET permission to listen on 127.0.0.1 and
  same-device clients (including browsers) to reach it without platform
  mediation. Termux hosting guides (Localtonet "How To Host A Web Server
  On Android"; DEV "Turn Your Android Phone Into A Local Development
  Server With Termux") document the same: a Termux server bound to
  127.0.0.1 is reachable from same-device apps/browsers; binding
  0.0.0.0 is only needed for other devices on Wi-Fi.
- This device PROVES the point: the 9H Minecraft app connected to the
  Termux 127.0.0.1:8765 listener twice. A blanket "Android isolates
  localhost per app" claim is therefore REJECTED for this setup.
- Remaining Android-specific realities (environmental, not bypasses):
  Android may suspend/background-freeze Termux (battery optimization,
  phantom-process behavior) while Minecraft is in the foreground, so a
  listener that answers curl/ws while Termux is foreground can still miss
  an accept while backgrounded. VPN / Private-DNS / proxy can also alter
  routing. Neither was measured during 9I. No security boundary is to be
  bypassed; if isolation ever becomes the finding it will be stated as a
  documented environmental limitation with the app in the foreground and
  a wakelock held.

## ROOT-CAUSE HYPOTHESES

- H1 — 127.0.0.1 is unreachable from the Minecraft app because Android
  isolates per-app loopback: REJECTED as a general cause. 9H proves two
  real connections over exactly this path on this device; authoritative
  sources agree loopback is device-shared. (A version-specific Minecraft
  regression remains UNKNOWN — see H6.)
- H2 — Listener was not bound/accepting when `/wsserver` ran (wrong
  timing, stale process holding the port, bad env): REJECTED for the 9I
  window as framed. The 9I server bound cleanly (capture file created,
  banner logged), a stale 9I-env holder was killed pre-run, and
  post-window TCP-OK proved the port accepted. Residual timing
  uncertainty (command typed before server `Waiting...`) is UNKNOWN, not
  evidence of a bind failure.
- H3 — Wrong URI / mistyped command (missing `ws://`, wrong port,
  `/connect` vs `/wsserver` confusion): UNKNOWN. No client-side command
  echo or error text was captured for 9I.
- H4 — World state rejected the command (cheats off, not Admin, or a
  stale `/wsserver` session never disconnected with empty string so the
  new attempt never dialed): UNKNOWN. 9H implies the owned test world
  can satisfy cheats/Admin, but the 9I attempt's world state and
  disconnect-first step were not recorded.
- H5 — VPN / Private DNS / proxy / firewall on the device intercepted or
  blocked the dial: UNKNOWN. Nothing was recorded about the 9I network
  configuration.
- H6 — Minecraft 1.26.52.3 build-specific loopback/websocket regression
  (Windows-UWP-style exemption does not exist on Android, but an
  equivalent bug could): UNKNOWN. No client error text exists to support
  or reject it; 9H shows the same-version path worked earlier the same
  day, which weighs against it but does not close it.
- H7 — Termux was background-suspended while Minecraft was foreground,
  so the accept never completed despite a healthy foreground listener:
  SUPPORTED (plausible, not proven). Fits all observations: foreground
  curl/ws/TCP-OK pass, 9I background window shows zero `[CONNECTED]`,
  no error server-side. Needs the wakelock+foreground controlled test
  to confirm or reject.
- H8 — Lab rejects Minecraft pre-upgrade (path, subprotocol, header
  filtering): REJECTED. No path filter is configured, the subprotocol is
  offered (not required), plain-HTTP behavior is the healthy 426, and a
  missing `[CONNECTED]` precedes all such logic.

Net: no PROVEN root cause for 9I exists. The single blocking gap is the
uncaptured Minecraft-side result (H3–H6) compounded by the untested
foreground/background variable (H7).

## SINGLE NEXT EXPERIMENT

Choice A — same-device 127.0.0.1 (controlled repeat; B/C expand exposure
without cause, D is unwarranted since 9H proves the path works).

Preconditions (all stay: my world, my device, localhost only,
observation/subscription only, no Exec, no injection, no auth bypass):

1. In Termux (foreground): `termux-wake-lock`, then
   `cd ~/xykell-client/lab/bedrock-websocket && mkdir -p captures &&
   SUBSCRIBE_ONLY=1 ENCRYPTED_SESSION=1 npm start 2>&1 | tee
   $PREFIX/tmp/xykell-9j-terminal.log`. Proceed only after `Waiting for
   Minecraft...` is visible. (Note: `/tmp/` is not writable on this
   Termux; `$PREFIX/tmp` is the documented equivalent.)
2. In the owned 1.26.52.3 test world (cheats ON, Admin): first run
   `/wsserver` with an empty string to drop any stale session, then run
   `/wsserver ws://127.0.0.1:8765` exactly. Photograph/transcribe the
   VERBATIM on-screen result (success line, or refused / timeout /
   invalid URL / permission-network text, or precisely "no visible
   result"), plus VPN/Private-DNS state.
3. Only after the terminal shows BOTH `[CONNECTED]` and exactly one
   `[HANDSHAKE] ... established` plus both subscribes
   (`PlayerMessage`, `PlayerTravelled`): send one chat, move several
   seconds, stop, move again, chat again, wait 15s, then stop the lab.
   If `[CONNECTED]` never appears within 60s, stop and report the
   Minecraft-side text — that alone is the result.
4. Preserve `$PREFIX/tmp/xykell-9j-terminal.log` and the new
   `captures/capture-*.jsonl`; do not interpret binary/156B/size/timing
   correlation as events — only `decodeInbound -> decrypt -> UTF-8 ->
   JSON -> envelope` counts.

Why exactly this: it isolates the two live variables (client-side
verbatim result; foreground/background scheduling) while changing
nothing about addressing, security posture, or protocol handling.

## SECURITY

- No production code, encryption implementation, protocol classifier, or
  capture parser was modified for this diagnosis (only this documentation
  file is added; the one synthetic diag capture created during listener
  verification was deleted to restore the 9I evidence set; no commit/push
  made).
- All probes were same-device loopback to the owned listener
  (`curl`/TCP/`ws` from Termux); no external hosts, no packet injection,
  no Exec beyond the lab's existing documented handshake/subscribe
  mechanism, no authentication bypass, no Android boundary bypass
  attempted or recommended.
- No secrets, keys, tokens, or world/personal data in this report; prior
  captures remain `redactedKeys`-clean; ephemeral keys from closed
  sessions are gone with their processes.
