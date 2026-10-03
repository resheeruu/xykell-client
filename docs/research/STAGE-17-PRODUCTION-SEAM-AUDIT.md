# Stage-17 production seam audit (read-only, docs-only)

No source, test, package, or manifest changes. No live producer
created. This audit asks whether the proven chain can enter
production cleanly — from repository evidence only.

## Repository facts

- Process/lifecycle: single-Activity app (`MainActivity`), no
  Service, no foreground service, no notification; `RuntimeManager`
  stub (`NOT_WIRED`); `LaunchExecutor` fires a system intent that
  backgrounds Xykell when Minecraft launches. Nothing keeps the
  Xykell process alive while Minecraft is foreground.
- Manifest: package-visibility query for Minecraft only. NO
  `INTERNET` permission, NO services. (Android requires INTERNET
  even for loopback sockets — adding it is a new permission.)
- Dependencies (`app/build.gradle.kts`): only
  `androidx.appcompat:appcompat`. No WebSocket/HTTP/crypto library.
- Provider system: `RuntimeProvider` (start/stop/state/
  capabilities/diagnostics/setSink) with `SyntheticRelayProvider`
  (test), `LanDiscoveryProvider`, `NativeProviderStub` (always
  fails, lab-gated). `CapabilitySink` already has a push-style
  `onChat(const ChatObservation&)` — a vocabulary seam, not a live
  path. `Runtime` selects providers by name; observation input seam:
  none.
- JNI (`bridge.cpp`): `NativeProfiles.*` + `RuntimeStatus.*`
  (start/stop/selectProvider/status/capabilities/endpoints/
  discovery/selectEndpoint/beginSession/markLaunched/endSession).
  No observation getters — exposing any would be new JNI.
- Threading: the ONLY background threads live in
  `lan_discovery` (announce/receive + mutex). No generic executor;
  a socket reader + `pollNext` pump would need newly owned threads
  (precedent pattern exists, machinery does not).
- Registry: generated (`generate.py`); capability entries require
  regeneration + validation and would over-claim with no live path.

## Questions A–H

A. Process ownership: the Xykell app process would own it, but it is
   NOT long-lived (no Service; Minecraft intent backgrounds it;
   killable at any time). Reliable ownership REQUIRES a new
   foreground service + notification. → CONDITIONAL.
B. User activation: only model (1) explicit user start/stop is
   supportable (`Runtime::start/stop` idempotent, nullable sink,
   PLAY-pipeline UX precedent). Silent/auto-start (2–4) have no
   basis and would violate the user-driven rule. → CONDITIONAL.
C. Localhost `ws://127.0.0.1:8765`: needs INTERNET permission
   (absent), socket/WebSocket code (absent; no dep available —
   new dependency or hand-rolled handshake), and envelope mapping
   (new code; `json_min` parses JSON but knows no Minecraft schema).
   No discovery/credentials/packets needed. → CONDITIONAL.
D. Lifecycle: Stage 16 proves Termux-lab survival, NOT Xykell
   production (different process, no service, unmeasured under
   Minecraft-foreground). Explicitly separated; production claim =
   UNKNOWN.
E. Stage-13 seam: a `LiveProducer` fits `pollNext()` + translator
   reuse with ZERO header changes (Stage 14 is that exact shape with
   a file behind it). → PROVEN compatible (shape only, not a live
   path).
F. Threading: no clean existing slot; precedent (lan_discovery)
   exists but new owned threads required. → CONDITIONAL.
G. Memory: CLEAN — snapshot/poll/index patterns are all bounded;
   a live producer preserving fixed-capacity/drop-oldest discipline
   is straightforward (to be documented if ever built).
H. Security: the boundary CAN hold (localhost-only, read-only, no
   Exec — as the lab demonstrates), but INTERNET permission +
   background component expand surface and need product review.
   No required change violates it outright. → CONDITIONAL.

## Decision matrix

| Area | Status |
|---|---|
| Localhost transport feasibility | CONDITIONAL (proven in lab; needs permission + code in production) |
| Production process ownership | CONDITIONAL (new foreground service + notification required) |
| User activation | CONDITIONAL (explicit start/stop only; silent/auto unsupported) |
| Minecraft foreground coexistence | CONDITIONAL (depends on service ownership above) |
| Android lifecycle | UNKNOWN (Stage 16 ≠ production; no soak; Stage-8 blocker 5 stands) |
| Stage-13 compatibility | PROVEN (zero-change fit, Stage-14 shape) |
| Translator reuse | PROVEN (no new parsing layer needed) |
| Threading seam | CONDITIONAL (precedent exists, machinery new) |
| Memory boundedness | CLEAN (patterns bounded; discipline documented) |
| Security boundary | CONDITIONAL (holdable, review-gated) |
| Dependency impact | CONDITIONAL (WS client code or new dep required) |
| Production architecture churn | MINOR (additive: producer + JNI getters + permission + service; no restructuring of existing components) |

## Decision

**PRODUCTION-SEAM-CONDITIONAL**

The seam is technically clean (contract fit proven, churn minor and
additive), but it requires explicit product/lifecycle decisions that
do not exist: foreground service + notification, INTERNET permission,
WebSocket client code/dependency, owned reader threads, explicit
user-driven start/stop UX, and a lifecycle soak under
Minecraft-foreground. CLEAN was not chosen because those are real new
components, not a free path; NOT-CLEAN was not chosen because nothing
requires rewrite, remote networking, credentials, or control.

## Validation

- Lab **58/58 PASS** (preflight; zero lab changes). Host **27/27**
  re-run in background (no host changes this stage).
- Structural audit: this stage adds one document; production source
  verified absent (no provider/JNI/UI/registry/networking diffs).
- No NDK build (existing Termux constraint — reported, not claimed).
  No live Minecraft experiment (no factual question required one).

## Security

Docs-only: no code, transport, crypto, permissions, or surfaces
added or altered. All hard restrictions held; U1 untouched.
