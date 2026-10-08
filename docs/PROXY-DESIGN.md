# Proxy design — DECIDED: local relay (path B)

Status: **BUILDING**. Owner directive (2026-10-06): parity with our reference
clients — **Lumina**, **WClient**, **Lunar** — through the relay path. This
supersedes the earlier RESEARCH_ONLY hold.

## 1. References (what we are parity with)

| Reference | What we take from it | License boundary |
|---|---|---|
| **Lumina** (GPLv3) | Relay architecture: local proxy terminates two RakNet sessions; modules register listeners with `beforeClientBound` / `beforeServerBound` hooks per packet; categories combat/motion/visual/world/misc/effect/game | Inspiration only — no GPL code in this MIT tree; clean-room, cite public protocol docs |
| **WClient** (GPLv3, legacy archive) | Module taxonomy + JSON config surface: combat (killaura, triggerbot, hitbox…), motion (fly, speed, bhop…), visual (ESP, fullbright…), misc | Inspiration only — no code copy |
| **Lunar** | HUD/QoL parity target (direction, armor, potion, crosshair, coordinates, keystrokes, arraylist) — overlay side, no relay needed | Proprietary; pattern-level only |

Originality rules in `docs/LEGAL-LICENSE.md` still apply: clean-room
implementation, no offsets/signatures/copied source; protocol behavior cited
from public specs (RakNet spec, Bedrock protocol docs, Cloudburst sources).

## 2. Architecture (path B)

```
Minecraft (device) ──UDP──▶ Xykell relay (this app, 127.0.0.1:<port>)
                               │  two independent RakNet + encryption legs
                               │  listener hooks: beforeClientBound /
                               │  beforeServerBound (original API)
                               ▼
                          real server
```

- User adds `127.0.0.1:<port>` as a server in the game — no root, no
  VpnService, no game-binary modification.
- Relay terminates the device leg with its own keys and opens a fresh
  upstream session; login packets are **forwarded untouched** (no credential
  interception, no token harvesting — `LEGAL-LICENSE.md` §3/§5).
- Modules are packet transforms behind the listener API. Advanced/combat
  modules: OFF by default, explicit warning, server-rules notice
  (`LEGAL-LICENSE.md` §4).

## 3. Phases

| Phase | Deliverable | Gate |
|---|---|---|
| **P1** | RakNet codec, pure Kotlin: offline handshake (ping/pong, open-connection 1/2), datagrams, frames, ACK/NACK | host unit suites |
| **P2** | Relay session: two-leg state machine, zlib batch `0xFE`, AES-256-CFB8+HMAC, listener API | host unit + loopback integration test |
| **P3** | Android shell: foreground service, Relay screen (target/port, per-module toggles, traffic counters), Advanced flag | typecheck + aapt2 + CI |
| **P4** | Module batches, host-testable transforms: ESP overlay, velocity strip, killaura inject, scaffold, fly/speed rewrites… | per-module host tests |
| **P5** | Registry flips per module (PARTIAL → evidence → device validation) | registry validate + audit + device runbook |

## 4. Non-negotiables

- No credential interception, no auth bypass, no server attacks, no DoS, no
  hidden traffic; relay is user-started, inspectable, stops cleanly.
- MIT tree stays GPL-clean: inspiration only from GPLv3 references.
- Ban risk and server rules are stated in-UI before first connect.
- Every phase host-testable; no claimed-working modules without tests.

## 5. Progress (updated 2026-10-07)

- **P1 done** — `RakNetCodec.kt` + `RakNetConnected.kt` (offline handshake,
  datagrams, frames, ACK/NACK wire format, Cloudburst-verified layout):
  suites #27 (16 tests) + #28 (15 tests).
- **P2 partial** — `RakNetSession.kt` per-leg dedup/ack/nack/retransmit
  (suite #29, 12 tests); **P2a transparent pipe** `RelayPipe.kt`: single
  loopback socket, byte-for-byte forward to one upstream — ACK/split/order
  pass through intact because both peers' seq spaces align through the pipe
  (suite #30 loopback integration, 4 tests). Pending: P2b stateful re-wrap
  (number re-assign → injection), `beforeClientBound`/`beforeServerBound`
  listener API.
- **P2c observation done (2026-10-07)** — `BedrockBatch.kt` (envelope
  0xFE + compressor byte + raw-deflate level 7 + SHA256 checksum trailer,
  AES-256-CTR key = SHA256(salt‖ECDH), JCE 32-bit counter caveat vs GCM
  documented) + suite #31 (9 tests, byte-exact against node vectors);
  `BedrockTap.kt` passive decrypt/observation (per-direction persistent
  ciphers, ServerToClientHandshake JWT salt+x5u catch) + suite #32
  (4 tests); `RelayPipe` raw observer hook (loopback both-directions test,
  suite #30 now 5 tests). Vectors regenerated with session semantics (one
  persistent cipher, counter = batch index). Gates: typecheck 92/34 PASS,
  kotlin unit 32/32 PASS.
- **P3 done** — `RelayService` (specialUse foreground service, manifest
  entry, notification shows endpoints only), `RelayFragment` screen
  (upstream host/port + listen port, persisted; Start restarts on edit),
  hub buttons Relay + restored Diagnostics. Gates: typecheck 90/32 PASS,
  aapt2 PASS, kotlin unit 30/30 PASS.
- **P4 packet codec + translator done (2026-10-07)** — `BedrockPackets.kt`
  (header varuint + Text 0x09 / SetTime 0x0A / MovePlayer 0x13 /
  SetHealth 0x2A decode, pmmp field order, null = unknown/malformed) +
  `TapTranslator.kt` (Batch → ChatMessage/Travelled/UnknownFrame +
  snapshot: self id learned from client-bound MovePlayer only, first
  packet baseline, delta-or-teleport emit, cumulative metres, honest
  "relay-text-unmapped-type" reason; Handshake ignored = P2b's job,
  PlayerAuthInput position deferred rather than fabricate travelMethod)
  + `ObservationExternal.kt` static sink (attach/detach = natural
  observation-off gating, no IPC surface). Wiring: `RelayService` builds
  BedrockTap+TapTranslator per pipe (observer exceptions guarded inside
  `RelayPipe` so forwarding never dies), `ObservationService` accept()
  under acceptLock with attach in beginSession/detach in tearDown.
  Suites #38/#39 (13+15 tests) + observer-throw test (suite #30, 6) +
  external-sink test (suite #24, 16). Gates: kotlin unit 39/39, unit
  37/37, typecheck 102/41, stage4-relay-audit PASS (12), i18n, JNI
  coverage, full-feature-audit no-fake, registry 258 OK; APK 3832233 B.
  Pre-key tap yields Handshake only → translator inert until P2b key.
- **P2b termination done (2026-10-08)** — `RelaySession.kt`: one session holds
  BOTH legs (SERVER role toward the game, CLIENT role toward upstream), which
  is what makes a transparent pipe insufficient — the relay has to end each
  session to re-sign the client identity. Owns one `RakNetEndpoint` per leg;
  four ciphers (device c2s/s2c, upstream c2s/s2c) because each leg has its own
  key. `BedrockIdentity` (EC P-384, signs the upstream login leaf);
  `RelayListener` fun interface + `RelayDirection` is the hook every module
  plugs into. Device leg: 0xc1 → 0x8f → 0x01 → pre-key 0x03 → sealed 0x04.
  Upstream leg: 0xc1 → 0x8f → login with the identity leaf swapped
  (`BedrockHandshake.rewriteLogin`) → pre-key 0x03 → sealed 0x04.
  Plaintext queues (cap 4096) hold traffic until the far side is up, then flush
  as one sealed frame. Shared helpers: `BedrockBatch.plainCandidates` (all
  pre-encryption frame shapes; the raw-datagram tap delegated instead of keeping a
  private copy), `BedrockHandshake.extractVarString`/`rewriteLogin`.
  Suite `RelaySessionTest` (4 tests: device handshake, upstream login rewrite
  to the relay identity, bidirectional forward through the listener, queue then
  flush in order) + a direct `rewriteLogin` test. The suite caught a real bug:
  server→game traffic was being sealed with the *upstream* cipher instead of
  the device one.
- **Modules batch done (2026-10-08)** — `runtime/modules/`: `ModuleWire`
  (bounds-checked varint/little-endian byte helpers) + 8 category objects, each
  a pure stateless `transform(id, direction, packet)` with an `IMPLEMENTED` set
  and an `IMPOSSIBLE` map carrying the concrete reason. 104 new tests across 8
  suites (48/48 total). Direction scoping is what makes them correct rather than
  merely plausible — `ghost` keeps the player's own outbound chat, `levitate`
  rewrites only the client's own report, `movementCorrection` drops only the
  server's rubber-band.
- **Observation + HUD batch (2026-10-08)** — the four `hud.*` ids that said "no
  read path; Stage-20 observation source absent" were blocked by a blocker that
  no longer existed once the relay terminated sessions, so the read path was
  built instead of the note being left stale. `VitalsObservation` carries
  observed `SetHealth 0x2A` and `SetTime 0x0A`; `EntityPopulationObservation`
  carries the relay's live entity counts. Both fields of vitals are optional and
  merge independently, because the two packets arrive on different cadences and
  "not observed" must never render as a zeroed health bar. `ticksPerSecond` is
  derived in the consumer from two SetTime samples exactly as `speedMps` is
  derived from two travel samples — absent until two samples exist, and a clock
  that stalls or goes backwards keeps the last valid rate. `hud.health`,
  `hud.low_health`, `hud.entity_counter` and `hud.tps` moved NOT_IMPLEMENTED ->
  PARTIAL; the population report is throttled to one line per second because an
  unthrottled one would repeat identical numbers on every packet. The raw-datagram
  `BedrockTap` was deleted: superseded by the session's plaintext, its only
  survivor was the `Event` vocabulary, which moved onto `TapTranslator`.
- **Two more dead-code bugs found while wiring (2026-10-08)** — same class as the
  `RelayListener.PASS` one. `EntityTable.observe` was called from nowhere, so
  `ctx.entities` was never populated and all 18 derived views (esp, tracers,
  nametag, block_tracers) silently saw zero entities; the runtime now feeds the
  table on every packet. And `ctx.updateSelf` only ran inside the *enabled*
  movement transforms, so with every module off the self id was never learned —
  which made the entity table count the player as their own target and left
  `speed` permanently unprimed. Session bookkeeping now happens once in
  `ModuleRuntime`, where every packet passes.
- **Modules wired (2026-10-08)** — the batch above was a library nothing called:
  `RelaySession` was only ever constructed in its own test with
  `listener = RelayListener.PASS`, nothing filled `ModuleContext.settings`, and
  `RelayService` drove the transparent `RelayPipe`, which cannot decrypt, so no
  transform could ever run. Four new pieces close that:
  `ModuleRuntime` (owns the session `ModuleContext`, dispatches all 48
  implemented ids in a fixed `ORDER`, re-reads the enabled predicate per packet,
  forwards rather than dies when a module throws, `onTick()` returns the due
  tap plans), `ModuleFlags` (pure `org.json` reader over the active profile's
  module map — the same map the Modules screen writes; unreadable ⇒ everything
  off), `RelaySessionDriver` (the UDP driver `RelaySession` never had: game
  socket bound on loopback, upstream socket connected, one loop polls both and
  ticks the session, idempotent close), and `RelayObservation`, which replaces
  the raw-datagram tap with the session's plaintext — the old path never
  received a key, so that observation feed was inert, and it observes *before*
  the modules so a `SetHealth 0x2A` dropped by `disabler` is still reported.
  `RelayService` now builds the runtime from the profile flags, installs
  `RelayObservation(runtime)` as the session listener, and reports
  STOPPED / HANDSHAKING / ONLINE distinctly (a bound socket is not a working
  relay). `ModuleTapRunner` replays the input-injection plans through
  `TouchAutomationService.playPlan` at 20 Hz — the same gesture surface a finger
  uses, so no UseItem or Interact packet is ever forged. 31 new tests
  (`ModuleRuntimeTest` 15, `ModuleFlagsTest` 9, `RelaySessionDriverTest` 4,
  `RelayObservationTest` 6, `ModuleTapRunnerTest` 8 across 5 suites);
  `run-kotlin-unit` 54/54, typecheck 120/56. Registry evidence corrected at the
  same time: `combat.velocity` claimed `SetActorMotion 0x1B` (that is
  EntityEvent — the id drift the suite caught) and the wrong leg, now `0x28`
  server→game.
- **Honest outcome of the 154 REFERENCE_ONLY ids** — 11 are genuinely
  deliverable from a relay and are PARTIAL (velocity, levitate,
  movement_correction, fullbright, time_changer, ghost, disabler,
  packet_monitor, packet_logger, proxy.mode, proxy.relay). The other 143 are
  NOT, and the registry now says why per id instead of the generic
  "out of scope by policy": they need input injection, a render/overlay pass,
  inventory or world state the relay never decodes, or state across packets.
  A packet hook cannot be an ESP overlay or an auto-clicker; delivering those
  would need the injection/render architecture, not a registry flip.
- **Remaining** — RelayService → RelayService socket-pump wiring of
  RelaySession (RelaySession is pure and socketless by design; a socket owner
  must drive both endpoints), `docs/XYKELL-REFERENCE-PARITY.md`, APK rebuild.

## 6. Termination & encryption design (locked 2026-10-06)

Evidence: CloudburstMC/Protocol 3.0 (`EncryptionUtils`, `BedrockPeer`,
`BedrockEncryptionEncoder/Decoder`, `CompressionCodec`), PrismarineJS
bedrock-protocol (`encryption.js`, `framer.js`, `keyExchange.js`), ProtoHax
(session listeners) — facts only, clean-room, no code copied.

**Key derivation (both references identical):**
`key = SHA256(salt || ECDH(localPriv, remotePub))`, EC P-384 (secp384r1).
Server handshake JWT: header `x5u` = server ECDH pub (self-declared, ES384
self-signed), payload `salt` = 1..64 bytes.

**Wire envelope (protocol >= 428):**
`0xFE || AES-256-CTR(compressorByte || deflateRaw(batch) || checksum8)`

- CTR: JCE `AES/CTR/NoPadding`, IV = `key[0..11] || 00 00 00 02`
  (GCM keystream equivalent; GCM = CTR block starting at counter 2).
- checksum8 = `SHA256(LE64(counter) || payload || key)[0..8]`, counter
  starts 0, incremented once per batch, per direction.
- compressorByte: `0x00` = raw deflate, `0x01` = snappy, `0xFF` = none.
- batch = repeated `varuint(len) || packet bytes`.
- `0xFE` sits OUTSIDE encryption; compressor byte inside.
- Ceiling: JCE CTR increments the full 128-bit block, GCM only low 32
  bits — diverges only after 2^32 cipher blocks (64 GiB) per session.

**Dual-leg termination (P2b):** relay runs two independent RakNet +
batch + crypto legs. Device leg: relay is the server (relay signs its own
ServerToClientHandshake JWT toward the game). Upstream leg: relay is the
client (intercepts the real server's handshake JWT, replies
`ClientToServerHandshake`). Result: relay holds both keys, plaintext is
available to listener hooks `beforeClientBound` / `beforeServerBound`.

**Login key swap:** server derives ECDH from `identityPublicKey` in the
login chain leaf — relay rewrites it to the relay keypair and re-signs
(offline: chain of 1, Cloudburst `validateChain` decodes without
verifying), and re-signs ClientData `extra` (server verifies it against
identityPublicKey). Online/Xbox mode needs a re-minted Mojang chain
(`multiplayer.minecraft.net/authentication` with the user's XSTS token) —
**deferred: legal review vs `LEGAL-LICENSE.md` §3/§5 first.** MVP targets
offline-mode servers.

**Compression:** snappy is the modern server default — pure-Kotlin
snappy-raw codec implemented (full decoder with bounds checks, literal-only
encoder; host BedrockBatchTest vectors + frame round trip, 2026-10-07).
Zlib path is the handshake default.

