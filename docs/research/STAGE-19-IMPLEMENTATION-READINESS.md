# Stage-19 implementation readiness gate (design-only, NOT IMPLEMENTED)

Gate question: is every implementation-critical decision specified
enough to begin production work? Sources: repo config (targetSdk 35,
minSdk 28, Java/Kotlin 17, single Activity, no services, appcompat
only, google()+mavenCentral()), official Android foreground-service
documentation (fetched for this stage), OkHttp API documentation
(fetched for this stage), and Stages 9–18 evidence. Nothing guessed
beyond these; residual unknowns are marked, never filled.

// Hard rules held: no production/Gradle/manifest/dependency/JNI/UI
// code; no live/network/Minecraft experiment; crypto frozen; U1
// untouched; translator/consumer/contract semantics unchanged.

## 1. FGS type (resolved as proposal, approval-conditional)

targetSdk 35 → each foreground service MUST declare
`android:foregroundServiceType`, request `FOREGROUND_SERVICE` + the
type permission, and pass the matching constant to `startForeground()`
(violations throw `MissingForegroundServiceTypeException` /
`SecurityException`; service must foreground promptly within the
system timeout after `startForegroundService()` — exact seconds
verified at implementation, not recorded here).
- `dataSync` DOCUMENTED AND REJECTED: its listed uses (upload/
  download, backup, import/export, fetch, local file processing,
  device↔cloud transfer) do not honestly describe a persistent
  game-observation socket — and Android 15 (our exact target, API
  35) adds runtime duration restrictions on `dataSync` services that
  directly threaten long observation sessions.
- `specialUse` PROPOSED: the documented catch-all for valid uses no
  other type covers, with `<property>` use-case declarations in the
  manifest. No other listed type (camera, location, microphone,
  connectedDevice, health, media*, phoneCall, remoteMessaging,
  shortService, systemExempted) is applicable.
- Consequence: NOT `FGS-TYPE-BLOCKED` (specialUse honestly fits), but
  approval-conditional — Play Console review applies IF ever
  Play-distributed (repo shows F-Droid-style/CI distribution; no Play
  listing exists), plus product sign-off. Start only from the
  foreground Activity (background-start restrictions satisfied by
  explicit user tap). No boot receiver (Android 15 restricts
  `BOOT_COMPLETED`→FGS launches — none proposed).

## 2. INTERNET permission (confirmed, confined)

Exact permission: `android.permission.INTERNET` (install-time, no
runtime prompt). Required because Android gates ALL app IP sockets —
including loopback — on it; `connect(127.0.0.1)` fails without it.
No additional network permission is necessary (no other socket class
used). Enforcement rule: endpoint constant `ws://127.0.0.1:8765`
only — reject `ws://localhost`, `ws://[::1]`, any IPv4/IPv6
alternative, any remote host, any user-configurable URL, any
redirect (OkHttp must be configured with `followRedirects(false)`;
non-127.0.0.1 resolved address = abort + FAILED). `::1` rejected as
untested (only 127.0.0.1 is proven). Manifest untouched this stage.

## 3. WebSocket dependency — OKHTTP-CONDITIONAL

OkHttp API fitness VERIFIED via official docs: `newWebSocket(Request,
WebSocketListener)`, binary `onMessage(WebSocket, ByteString)`,
`send(ByteString)`, graceful `close(code, reason)`; subprotocol via
`Sec-WebSocket-Protocol` request header (`com.microsoft.minecraft.
wsencrypt`); lifecycle shutdown via close/cancel. Compatibility:
OkHttp 4.x/5.x needs API 21+/Java 8+ — inside minSdk 28 / Java 17;
resolvable from the repo's existing google()+mavenCentral(). NOT
verified: exact AAR footprint and transitive tree (okio + Kotlin
stdlib expected) — unclaimable without a Gradle resolve, which is
forbidden this stage. Hence CONDITIONAL (fit proven, footprint
pending resolve-at-implementation). Rejected alternatives: Java-
WebSocket (thinner maintenance), nv-websocket-client (stale),
hand-rolled RFC 6455 (highest review burden — fallback only if
dependency policy forbids OkHttp).

## 4. JNI boundary — JNI-MINIMAL

Necessary: transport lives in Kotlin, the verified consumer lives in
native — reimplementing the consumer in Kotlin would duplicate
verified logic (rejected); native-side sockets would be a worse new
stack (rejected). Exact proposed surface, field-only, mirroring the
Stage-10 factories (invalid → dropped, never stored):
`nativeOfferPlayerMessage(eventId: String, observedAtMs: Long,
sender: String, message: String)`,
`nativeOfferPlayerTravelled(eventId, observedAtMs, x/y/z/yaw/meters:
Double, travelMethod: Int)`,
`nativeOfferUnknown(wireLength: Long, reason: String, observedAtMs:
Long)`. No JSON/envelopes/buffers/objects/crypto/protocol strings
(except the already-required sender/message/reason text)/control
methods/generic bridge. NOT implemented.

## 5. Encryption boundary (frozen, isolated)

Stage-9 behavior frozen (algorithms, parameters, Base64, subscriptions;
no U1 work; no persistence/logging of keys/salts/ciphertext; no
auth/credential contact). Placement: reader thread → frame decrypt →
plaintext JSON → translator — all Kotlin-side inside the future
producer; JNI receives observations ONLY, never key/IV/ciphertext/
envelope material. The Node lab is not portable as-is: a clean-room
Kotlin/Java port of the PROVEN PARAMETERS (JCE supplies ECDH/AES, no
new dep) is required, itself gated by a dedicated evidence stage
before it may feed anything.

## 6. Buffer contract (fixed)

Capacity 64 `RuntimeObservation` items (already-translated — the
preferred form, so the queue never holds envelopes/frames). Full =
DROP-OLDEST + monotonic `overflowCount` in status (freshness beats
completeness; loss visible, never silent). Malformed frame →
dropped + malformed counter (lab `Invalid` semantics); unknown event
→ counted metadata (lab `Unknown` semantics); duplicate → latest-wins
(consumer already does); service stop → discard handoff, snapshot
already terminal; process death → everything gone (no persistence by
design — fresh start is the empty snapshot).

## 7. Failure state machine (frozen)

IDLE →(Start) STARTING →(established+subscribed) OBSERVING →(Stop)
STOPPED; any failure from STARTING/OBSERVING → FAILED with verbatim
reason (refused / not-connected / encryption / malformed / subscribe /
close / stopped / death). Default NO automatic reconnect (bounded
retry only as future explicit user-approved policy — none proposed).
Fresh session after FAILED = user taps Start again (new ephemeral
keys, empty snapshot, reset overflow).

## 8. Notification contract (specified, not created)

State + counts + Start/Stop actions only (same explicit controls).
NEVER: chat text, usernames, coordinates, keys/salts/ciphertext,
diagnostic payloads, raw socket data. Dedicated notification channel
required (standard since API 26; channel creation is ordinary
`NotificationManager` code at implementation).

## 9. Lifecycle soak criteria (defined, not run)

Post-implementation gate, each step PASS/FAIL observable from status/
snapshot/capture-equivalent log: (1) Activity starts service →
STARTING logged; (2) Minecraft launches (service stays foreground);
(3) established+subscribed within 60 s; (4–6) Minecraft foreground,
Activity backgrounded, app-switch cycle, still foreground service;
(7) screen off/on 30 s → service alive, socket open; (8) post-gap
chat+walk decrypt to verified envelopes on the SAME connection;
(9) Activity resumes → snapshot/timestamp coherent; (10) Stop →
STOPPED, socket closed; (11) teardown verified (service destroyed,
threads joined, snapshot empty, no wake-lock held); (12) no stale
state (fresh Start yields new session id, zero counters). FAIL on any
reconnect-masked gap, fabricated state, or missing teardown artifact.

## 10. Security gate (enforceable as specified)

localhost-only (§2 constant + review), observation-only (subscribe +
poll + snapshot reads; zero Exec/command/injection/rewrite in the
plan), explicit activation (§7 FSM, no auto paths), no remote/TLS/
config surface, no credentials/auth (no such code exists to call),
no anti-cheat/server interaction beyond the user-typed `/wsserver`
the lab already uses. Enforcement points: endpoint constant,
zero-config UI, factory validation, JNI field-only surface,
audit-grep pattern (Stages 11–13), forbidden-list below.

## Implementation manifest (future only — files NOT created)

| File | Purpose | New/Modified | Security boundary |
|---|---|---|---|
| manifest `<service>` + specialUse + 3 permissions + INTERNET | declare service/type/permissions | Modified | localhost-only review point |
| `service/ObservationService.kt` | FGS lifecycle, owns reader+handoff+producer | New | no Exec/command surface |
| `net/LoopbackWebSocket.kt` | OkHttp socket, fixed URL, redirects off | New | constant endpoint; redirects abort |
| `net/EnvelopeCrypto.kt` | clean-room port of frozen params | New | ephemeral-only; zero key logging |
| `net/ObservationTranslator.kt` | envelope→model mapping (Stage-11 rules) | New | field-only output |
| `net/LiveProducer.kt` | pollNext/status over handoff | New | bounded, no auto-reconnect |
| `jni/ObservationOffers.cpp` + Kt decls | 3 offer functions | New | field-only; invalid dropped |
| `ui/ObservationControls.kt` | Start/Stop + status text | New | explicit activation only |
| notification channel setup | FGS presence | New (in service) | counts/state only |
| `runtime_event_observation.h` etc. | — | FORBIDDEN (unchanged) | contract frozen |
| `RuntimeProvider`/registry/GameState | — | FORBIDDEN until live verified | no over-claim |
| crypto/protocol/translator/consumer semantics | — | FORBIDDEN | frozen evidence |

Phase 1 (minimum live session): manifest + service + socket + crypto
port + translator + producer + handoff + JNI offers + controls +
channel + tests + soak run. Phase 2: soak validation per §9 (gate,
not code). Phase 3 (NOT initial implementation): capability
advertisement/UI polish — only after Phase-2 passes.

## Validation

- Lab **58/58 PASS**; host **27/27** re-run in background (no host
  changes this stage). Nothing weakened/deleted; crypto untouched.
- Structural audit: this stage adds one document; production source
  verified absent (no provider/JNI/UI/registry/network diffs).
- No NDK build (existing Termux constraint — reported). No network/
  live-Minecraft work (no factual question required it).

## Decision

**IMPLEMENTATION-CONDITIONAL**

Ready-in-shape but not cleared to build: FGS type resolved as
specialUse-proposed (approval-conditional, §1); OkHttp fit-verified
but footprint-pending (§3); JNI/crypto/buffer/FSM/notification/soak/
security all specified (§§4–10); the implementation file set is exact
(manifest above). READY requires the outstanding approvals (service +
notification UX, INTERNET permission, OkHttp resolve, JNI surface)
plus a passed Phase-2 soak; BLOCKED does not apply (no rewrite,
remote, credential, or control requirement exists anywhere in the
plan).
