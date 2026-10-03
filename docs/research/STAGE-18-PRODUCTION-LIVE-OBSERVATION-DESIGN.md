# Stage-18 production live-observation design (design-only, NOT IMPLEMENTED)

Turns Stage-17 PRODUCTION-SEAM-CONDITIONAL into a reviewable build
plan. Zero code, manifest, Gradle, dependency, JNI, UI, registry, or
runtime changes (verified at close). Lab 58/58; host 27/27 re-run.

// Repo facts used (no other platform claims): compileSdk/targetSdk
// 35, minSdk 28, Java/Kotlin 17, NDK-via-CI (phone never builds APKs),
// single Activity, no Service/receiver/provider declared, no INTERNET
// permission, one dependency (appcompat), google()+mavenCentral()
// repos, threading precedent only in lan_discovery, JNI = profiles +
// RuntimeStatus only, provider system = synthetic-relay default /
// lan-discovery / native-stub with CapabilitySink vocabulary.

## 1. Ownership (REQUIRED FOR RELIABLE PRODUCTION OWNERSHIP: foreground service)

Activity-bound ownership is insufficient by repo evidence: single
`MainActivity`, and `LaunchExecutor` backgrounds Xykell when Minecraft
launches — the observer would die exactly when needed. JobScheduler/
WorkManager are periodic-work shapes, wrong for a persistent socket,
and neither exists in the codebase. Minimum viable model: Activity
(explicit Start) → Foreground Observation Service → localhost socket →
translate → bounded handoff → `PollingSource` → consumer → snapshot;
Activity (explicit Stop) tears it down. No existing lifecycle
mechanism suffices (evidence above); the service is REQUIRED, not
assumed — and still needs product approval (decision §14).

## 2. User activation (explicit only)

 PLAY-style Start/Stop controls owned by the Activity; states:
 IDLE → STARTING → OBSERVING → STOPPED/FAILED with verbatim reason.
 Defined outcomes: Minecraft not running → `WAITING` visible state,
 no silent retry storm (bounded: zero automatic reconnects by
 default; user taps Start again); `/wsserver` not connected → TCP
 refused shown; encryption/subscription failure → exact stage shown,
 torn down; Minecraft closes → close observed, state to STOPPED with
 reason; Activity reopened → reads snapshot/status only, never
 auto-starts. Silent/auto/hidden/background-without-action startup:
 FORBIDDEN by design (no manifest receiver, no boot path, no
 auto-start code exists to add it to).

## 3. Notification (minimum, if service required)

Purpose: statutory foreground-service presence + observation state
(IDLE/OBSERVING with counts, FAILED with reason). Start/Stop actions
only (same explicit controls, no new semantics). MUST NOT expose:
message text, sender names, positions, keys, salts, diagnostics
beyond counts. Remains while Minecraft is foreground (that is the
point: the service outlives the Activity). Not created this stage.

## 4. INTERNET permission (why + restriction)

Android gates ALL app IP sockets (including loopback) on INTERNET —
without it `connect(127.0.0.1)` fails; that is why it is technically
required despite zero internet use. It represents full network
capability, so the design confines it: endpoint hardcoded as the
constant `ws://127.0.0.1:8765` (no host/port config surface, no
input field, no discovery); any non-127.0.0.1 endpoint rejected in
code review; IPv6/`::1` rejected (untested — 127.0.0.1 only, as
proven); no TLS/remote code paths (plaintext loopback only, matching
the proven lab). Manifest untouched this stage.

## 5. WebSocket implementation (DESIGN DECISION — NOT IMPLEMENTED)

Gradle env: google()+mavenCentral() at CI build time; nothing
vendored; install/download forbidden this stage. Realistic options
(sizes approximate, labeled, not vendored):
 - OkHttp (+okio, ~1 MB): maintained, binary frames, subprotocols,
   no TLS needed, localhost-only enforceable by fixed URL; largest
   but standard. PROPOSED.
 - Java-WebSocket (~150 KB, single artifact): smaller, thinner
   maintenance, manual threading.
 - nv-websocket-client (~200 KB): small but stale maintenance —
   rejected on maintenance burden.
 - Hand-rolled RFC 6455 on `java.net.Socket` (~200 lines incl.
   client masking): zero deps, full endpoint control, but custom
   protocol code in production = highest review burden — rejected
   unless dependency policy forbids OkHttp.
All support binary frames + subprotocol offer; none need TLS here.
Maintenance verdict: OkHttp unless product policy says otherwise.

## 6. Encryption (frozen evidence, isolated reuse)

Stage-9 parameters frozen (P-384 SPKI, 16-B salt, standard-unpadded
Base64, ECDH-x → SHA-256(salt+secret), CFB8, IVs=key[:16]) — no
redesign, no improvement, no U1 work. Isolation: the Node lab code
CANNOT run in production as-is (different runtime); production needs
a clean-room Kotlin/Java port of the PROVEN PARAMETERS (JCE provides
ECDH/AES with no new dep), built behind the future live producer and
gated by its own evidence stage — stated here so no one mistakes
parameter-frozen for code-portable. Requires: ephemeral keys only,
zero persistence, zero key/salt/ciphertext logging, zero credential
storage/auth interception. U0/U1 policy unchanged (outside event
translation, metadata-only).

## 7. Threading (one reader + bounded handoff + synchronous poll)

One socket-reader thread → fixed-capacity handoff (proposed cap 64
observations) → `pollNext()` drains synchronously on the consumer
side. Full behavior: DROP-OLDEST with an overflow counter surfaced
in status (justified: consumer keeps latest-wins snapshots, so
freshness beats completeness; counter makes loss visible, never
silent). No implementation; no queue created. Precedent shape follows
lan_discovery's owned-threads+mutex, but the machinery is new.

## 8. Stage-13 compatibility (proven by shape)

Future `LiveProducer.pollNext()/status()` satisfies the existing
contract with ZERO changes to all three headers (Stage 14 already
demonstrates the identical shape against files). Live items flow:
socket bytes → decrypt (6) → translate (Stage-11 reuse, unchanged) →
`RuntimeObservation` → `PollingSource` → consumer → snapshot. No
`LiveProducer` created; no interface modified.

## 9. JNI (REQUIRED, minimal, conditional on Kotlin transport)

With transport in Kotlin and the consumer in native, the snapshot
must be fed across the boundary: exactly three offer-functions,
fields only (no JSON/envelopes/bytes cross):
 `nativeOfferPlayerMessage(eventId, observedAtMs, sender, message)`,
 `nativeOfferPlayerTravel(eventId, observedAtMs, x, y, z, yaw,
 meters, travelMethod)`, `nativeOfferUnknown(wireLength, reason,
 observedAtMs)` — each constructs via the Stage-10 factories (invalid
 → dropped, counted as Invalid-receipt in status, never stored).
Alternative (consumer re-implemented in Kotlin) rejected: duplicates
verified native logic. NOT implemented; surface enumerated so its
smallness is reviewable.

## 10. Runtime integration (concept, zero churn)

Service → LiveProducer → translate → JNI offers → existing
`ObservationConsumer` → snapshot; `Runtime` exposes snapshot reads
alongside existing `session()` (read path only). `RuntimeProvider`
implementations need NO changes (observation flow stays parallel to
the provider system, as in Stages 12–14); advertising anything in
capabilities/registry is explicitly OUT until a live source is
verified. If review finds this parallel-stack placement
unacceptable, that finding itself is the documented problem — no
code is moved preemptively.

## 11. Failure behavior (explicit user control by default)

Refused / Minecraft-not-connected / encryption fail / malformed
frame / unknown event / subscribe fail / close / service-stopped /
process death → terminal state STOPPED/FAILED with verbatim reason,
session torn down, NO automatic reconnect (default; bounded retry
only ever as an explicit user-approved policy, none proposed).
Malformed → dropped + counted (lab `Invalid` semantics);
unknown → counted metadata (lab `Unknown` semantics). No retry
storms, no silent restarts, no state fabrication on death (fresh
start = empty snapshot, Stage-12 initial state).

## 12. Security boundary (enforcement points)

localhost-only (hardcoded endpoint + review-gated rejection of
others, §4); observation-only (subscriber + poll + snapshot reads;
no Exec/command/injection/rewrite anywhere in the plan); no remote
endpoints (no TLS/config surface to point elsewhere); no credential/
auth handling (no such code exists to call); no anti-cheat/server
interaction beyond the user-typed `/wsserver` the lab already uses.
Enforcement = constant endpoint, zero config surface, factory
validation, audit grep (Stage-11/12/13 pattern), and the explicit
forbidden-list below.

## 13. Android 16 (repo-grounded only)

Device API 36; targetSdk 35 → Android-14+ foreground-service rules
apply (declared service + `foregroundServiceType` + permission +
timely `startForeground` + user-visible notification). The exact FGS
type value is UNKNOWN from repo evidence (no service exists to type)
and is a product/platform decision, not a guess recorded here.
Activity→Service lifecycle (explicit start/stop intents) uses only
long-present APIs. No other And
...[truncated 1583 chars]