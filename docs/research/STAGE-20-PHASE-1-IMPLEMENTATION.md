# Stage-20 Phase-1 implementation (read-only live observation path)

Phase-1 implementation candidate under validation. No capability
advertisement, no auto-start/reconnect, no remote networking, no
control/credential surface. Nothing committed or pushed.

// Preflight: the five baseline docs plus current tree matched every
// Stage-19 assumption (single Activity, no services, INTERNET absent,
// appcompat-only deps, JNI profile/status style, org.json available,
// lab crypto/translator shapes). No contradiction found. FGS timeout
// resolved authoritatively (service must startForeground() within 5 s
// of startForegroundService; ForegroundServiceDidNotStartInTimeException
// otherwise) — service foregrounds FIRST, before any network work.

## Files changed/added

- `app/src/main/AndroidManifest.xml` (M): INTERNET +
  FOREGROUND_SERVICE + FOREGROUND_SERVICE_SPECIAL_USE; service
  `specialUse`, `exported=false`. Nothing else.
- `app/build.gradle.kts` (M): `okhttp:4.12.0` + junit 4.13.2
  (test-only). Dependency footprint unverified (no resolve on-device).
- `runtime/observation/ObservationCrypto.kt` (new): clean-room port of
  frozen Stage-9 parameters (P-384/secure-random salt16/std-unpadded
  Base64/ECDH-x→pad48/SHA-256(salt+secret)/AES-CFB8 streams/IV=key[:16]);
  JCE-only, pure JVM, zero logging of material.
- `runtime/observation/ObservationTranslator.kt` (new): Stage-11
  semantics mirrored (same validation/mapping/Unknown-vs-null split;
  travelMethod integral-only at the JNI-Int boundary — stricter than
  the lab by necessity, documented in code).
- `runtime/observation/LiveProducer.kt` (new): cap-64 handoff,
  drop-oldest + monotonic overflow, synchronous poll; translated
  items only.
- `runtime/observation/LoopbackWebSocket.kt` (new): fixed-endpoint
  OkHttp transport (endpoint `require()` + host/port/scheme asserts,
  `followRedirects(false)`); handshake/key/subscribes only; decrypt-
  then-parse inbound; single-establishment guard; no reconnect.
- `runtime/observation/ObservationStateMachine.kt` (new): pure
  IDLE/STARTING/OBSERVING/STOPPED/FAILED, explicit transitions only.
- `runtime/observation/ObservationService.kt` (new): FGS owner,
  channel + counts/state-only notification, owns pipeline, terminal
  teardown, START_NOT_STICKY (death = nothing persists).
- `runtime/observation/Observations.kt` (new): 3 field-only external
  offer funs + guarded wrappers.
- `app/src/main/cpp/bridge.cpp` (M): shared native consumer +
  3 offer impls through Stage-10 factories (invalid → false, never
  stored). Existing JNI style followed.
- `ui/HomeFragment.kt` + `fragment_home.xml` (M): Start/Stop buttons
  + state text. No settings, no endpoint UI, no diagnostics.
- `app/src/test/.../ObservationPipelineTest.kt` (new, JUnit4, CI-run):
  endpoint accept/reject table, FSM, cap-64/overflow, translation
  fixtures, crypto parity vectors, endpoint-constant security test.

## Dependency versions

OkHttp 4.12.0 (minSdk 21+ vs repo 28; Java 8+ vs repo 17; binary
frames, subprotocol header, close/cancel — API-verified via official
docs). junit 4.13.2 test-only. Resolution + footprint unverified
on-device (no SDK/network for Gradle) — acceptance item, not claim.

## Service lifecycle / notification / endpoint / encryption / JNI /
## buffer / state machine

As designed in Stage 18 and implemented above: foreground-first
(5 s rule), channel `xykell_observation` IMPORTANCE_LOW, counts/state
notification surviving Minecraft-foreground, constant endpoint with
immediate-fail enforcement, ephemeral-only frozen crypto, 3 field-only
JNI offers, cap-64/drop-oldest/monotonic, IDLE→…→STOPPED/FAILED with
zero auto-reconnect, fresh Start = new keys + empty snapshot.

## Tests

- Host **27/27 PASS**, lab **58/58 PASS** (no behavior change either
  side; crypto/lab suites untouched).
- New JVM suite written for CI with hardcoded Node-generated parity
  vectors (Base64 alphabet quad; fixed-ECDH peer SPKI→48 B secret;
  KDF digest `726f9b54…`; CFB8 ciphertext vector + roundtrip +
  streaming chunks; endpoint/FSM/buffer/translation/security cases).
  JVM execution UNAVAILABLE on-device (no kotlinc/Gradle/SDK):
  written, not run — reported, never claimed.
- Structural audit: new production code capability-clean (hits are
  doc-negation words, the internal `ws://`→`http://` parse step under
  the constant-enforced endpoint, and "remote close" protocol
  terminology). Frozen files zero-diff; registry/GameState/provider
  untouched; no advertisement.

## Known limitations (acceptance-relevant)

1. Android compile unverifiable on-device (no SDK/aapt2/Gradle
   daemons usable here; repo notes phone never builds APKs).
2. OkHttp resolution + footprint unverified (needs Gradle resolve).
3. JVM tests written, not executed (no kotlinc/JUnit runner here).
4. No live Minecraft/device run performed (user session + CI build
   required). JNI/C++ additions review-compiled by inspection only.
5. FGS specialUse review standing (product/Play determination).

## Acceptance assessment (honest, item-by-item)

1. Android app builds — UNVERIFIED (limitation 1).
2. Service starts explicitly — implemented, unexecuted.
3. Notification appears — implemented, unexecuted.
4. Endpoint enforced — implemented + JVM-test written, unexecuted.
5. Encryption tests pass — vectors + tests written, unexecuted.
6–9. WebSocket/subs/events — implemented, unexecuted live.
10. JNI receives observations — implemented, review-only verification.
11. Native snapshot updates — via unchanged Stage-12 consumer path.
12. Stop tears down — implemented, unexecuted.
13. No auto-reconnect — by construction + FSM test (written).
14. No capability advertisement — verified (registry untouched).
15. Security audit passes — passes on inspection (above).

Phase-1 acceptance: PARTIAL (implementation complete to spec;
device/build/live claims all withheld pending CI build + dependency
resolve + JVM suite + guided live session). NOT a device/Minecraft
claim. Hard-stop conditions: none triggered (FGS resolved without
guessing; no substitution needed — resolution itself is merely
unrunnable here; parity vectors generated from the lab, algorithm
untouched; JNI stayed 3 field-only functions; provider untouched;
endpoint enforced in code; redirects disabled; no control/credential
need arose; boundary unambiguous).
