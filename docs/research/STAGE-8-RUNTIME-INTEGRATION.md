# Stage-8 runtime integration (surface inventory + boundary verdict)

## Installed environment (user's own copy, read-only aapt inspection)

- com.mojang.minecraftpe 1.26.52.3 (972605203), minSdk 32 / target 36,
  splits: base + arm64_v8a + en + xxhdpi + install_pack. (Updated since
  Stage 3's 1.26.45.1 reading; same outcome.)
- Exported: MainActivity (MAIN/LAUNCHER + VIEW file/* + VIEW BROWSABLE
  `minecraft://`, singleTask); XAL IntentHandler (VIEW + auth host —
  Microsoft auth callback surface, DO NOT TOUCH); Amazon IAP receiver;
  Braze/Firebase receivers; ImportService; billing/Play-Games activities.
- Not exported: FileProvider, MC services, game internals. Native entry
  `android.app.lib_name = minecraftpe` (arm64 split).
- 15 permissions (INTERNET, LICENSE CHECK, MULTICAST, BILLING, FGS, ...).

## Boundary classification

- LAUNCHABLE = YES (system intent + `minecraft://` deep links; both public).
- OBSERVABLE = NO (no exported providers, no game-state broadcasts, no
  documented external state API on retail Android).
- ATTACHABLE = NO (no plugin/embedding API; no legitimate load path).
- CONTROLLABLE = NO (deep links are inbound requests MC acts on; Xykell
  cannot drive runtime behavior through them).

## Candidate mechanisms (all rejected or deferred with reason)

1. Exported-activity invocation — ACCEPTED for launch only (implemented
   Stage 7). Not observation.
2. `minecraft://` URIs — launch/join requests; one-way inbound; no state
   back. Usable for launch UX, not runtime. RESEARCH (URI action table).
3. XAL/auth browser flow — user authentication surface; touching it risks
   credential interception. REJECTED (never implement).
4. Content providers/files — FileProvider not exported; SAF/user-grant
   file management is legitimate but is the management plane, not runtime.
5. Scripting API — in-game JS, no external observation channel. NOT
   APPLICABLE to an external client.
6. Dedicated server interop — legitimate lab direction (self-hosted BDS +
   owned clients) but no environment here. RESEARCH (lab-gated).
7. WebSocket code-connection (`/wsserver`, game-initiated localhost
   outbound, documented Microsoft feature) — strongest legitimate
   OBSERVABLE candidate: user enables in-game, game streams events to a
   local Xykell endpoint. NOT implemented: needs interactive lab
   (enable + join + observe) plus protocol mapping. RESEARCH (lab-gated).
8. Injection/hooks/patching/ptrace/mem — REJECTED (bypass by definition).

## Decision: PATH B

No legitimate runtime surface is implementable in the current authorized
environment. Runtime Integration: BLOCKED-ENV. The WebSocket localhost
direction is the prime re-entry candidate for a future authorized lab.
