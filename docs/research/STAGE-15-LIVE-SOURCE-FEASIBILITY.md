# Stage-15 live-source feasibility gate (decision/evidence, docs-only)

No code changed, no network experiment run, no crypto touched, no
production surface added. This stage answers whether a live Minecraft
source may feed the proven chain — from existing evidence only — and
records exactly one decision. UNKNOWN is never upgraded to PASS.

## Evidence matrix

| Requirement | Evidence | Status |
|---|---|---|
| Minecraft localhost WebSocket transport | Stage 9: 6 accepted sessions, MC 1.26.52.3 → 127.0.0.1:8765 same-device listener (9H×2, 9M, 9N, 9P×2) | PROVEN (lab transport) |
| Encrypted session establishment | Stage 9: exactly-once establishment + single subscribe batch every accepted session; 4 consecutive error-free sessions (9M–9P) | PROVEN |
| PlayerMessage observation | Stage 9N/9P: 3 sessions, verbatim user text, envelope-verified | PROVEN |
| PlayerTravelled observation | Stage 9N/9P: 51 cumulative events, coherent telemetry, envelope-verified | PROVEN |
| Real encrypted-frame decryption | Stage 9N/9P: 55/57 post-establishment frames decrypt (9O explains the 2 non-JSON frames as in-stream, JSON-stage failures) | PROVEN |
| Translator compatibility | Stage 11/14: 54/54 real records map with zero field loss; cross-check JSON-identical | PROVEN |
| PollingSource compatibility | Stage 13/14: CaptureProducer drives the exact outcome shape end-to-end (26+28 records) | PROVEN |
| Consumer compatibility | Stage 12/14: snapshot ends message 1/1, travel 24/26, unknown 1/1 per session | PROVEN |
| Android lifecycle reliability | No lifecycle soak exists (Stage-8 blocker 5: BLOCKED-ENV, cannot install from Termux UID); 9J-H7 background-suspend never tested; wake-lock used but unvalidated under Minecraft-foreground | UNKNOWN |
| Background reliability | Same gap: listener proven while Termux accessible; survival while Minecraft is foreground unmeasured | UNKNOWN |
| User-driven activation | Lab procedure proven (documented `/wsserver`, owned world, Code-Connection model); as a Xykell production UX with an owned listener surface: no evidence | UNKNOWN |
| Production integration seam | `Runtime` has no observation input (Stages 12–13 deferred deliberately); a live path needs networking permission, background execution, new dependencies — none shown clean | UNKNOWN |
| Security boundary | Chain-wide audits clean; observation-only by construction at every layer; nothing exists that could breach it | PROVEN (architecture) |

## Question answers

1. Live transport: PROVEN for lab observation (same-device localhost,
   user-typed `/wsserver`, 6 sessions). NOT proven as a production-app
   capability — production has no networking at all.
2. Live encryption: PROVEN sufficient as observation transport
   (enableencryption → standard-Base64 → P-384 → establishment →
   subscriptions → persistent-CFB8 decryption of real frames). Frozen;
   U1 not reverse-engineered (still `UNKNOWN_FRAME`).
3. Production legitimacy: NOT PROVEN. Observation-only/user-driven/
   localhost-only operation is demonstrated practice in the lab, but
   no production surface exists to attach those properties to. Marked
   NOT PROVEN per the rule.
4. Android lifecycle: UNKNOWN. Two apps cannot share the foreground;
   the listener's survival while Minecraft is foreground and Xykell is
   backgrounded has never been measured, and Stage 8 records the
   lifecycle soak as environmentally blocked. No reliability claimed
   from theory (9J-H7 stays a hypothesis).
5. Clean seam: architecturally PROVEN compatible (a `LiveProducer`
   could implement `pollNext()` + reuse the translator with zero
   changes to the three headers — Stage 14 is that shape with a file
   behind it), but as a production integration it is UNKNOWN (no
   seam demonstrated, and none will be cut without a verified source).

## Decision

**LIVE-SOURCE-NOT-PROVEN**

Required production facts — lifecycle survival, background
reliability, user-driven activation through Xykell, and a demonstrated
integration seam — remain theoretical. The laboratory transport being
proven does not transfer to production; the rules require the negative
decision and it is recorded without forcing.

## Preserved architecture

Recorded Evidence → CaptureProducer → PollingSource →
ObservationConsumer → Snapshot remains the completed architecture. A
future live producer must satisfy the exact Stage-13 polling contract
and reuse Stage-11 translation unchanged — the seam is specified, the
source is absent.

## Validation

- Lab suite: **58/58 PASS** (preflight; zero lab changes this stage).
- Host suite: **27/27 PASS** (background re-run, pre-existing tree;
  this stage adds no host code).
- No NDK build (existing Termux SDK/network constraint — reported,
  not claimed). No live Minecraft session run (existing evidence
  sufficient for every question above).

## Security

Docs-only stage: no code, no transport, no crypto, no permissions,
no JNI/UI/registry/provider changes (verified via status: this stage
adds only this document). All hard restrictions held.
