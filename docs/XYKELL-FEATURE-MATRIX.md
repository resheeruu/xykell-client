# XYKELL Master Feature Matrix (end-to-end program)

Authoritative program tracker. `registry/features.json` (255 entries) remains
the machine truth for module-catalog status; this document is the
milestone-level map with evidence, test, device, and security columns.

Statuses: RESEARCHED, SPECIFIED, IMPLEMENTED, AUTOMATED, CI-VALIDATED,
DEVICE-SMOKE-VALIDATED, RELEASE-READY, RESEARCH_REQUIRED, UNAVAILABLE.
Security: LOW (local/UI/config) / GUARDED (game-adjacent, gated, OFF-default)
 / FORBIDDEN (never: bypass/exploit/credential/auth/remote-control).

Columns: ID | Category | Reference evidence | Observed behavior |
Xykell requirement | Required runtime/data surface | Implementation status |
Automated test status | Device-only requirement | Security | Dependencies/blockers

## Tier 1 — Foundation (current focus)

| ID | Category | Reference evidence | Observed behavior | Xykell requirement | Required surface | Impl | Tests | Device-only | Sec | Deps/blockers |
|---|---|---|---|---|---|---|---|---|---|---|
| F-LAUNCH | launcher | Levi (Apache-2.0, APK verified) | staged checks, honest blockers | PLAY pipeline w/ exact failure text | PackageManager, intents | IMPLEMENTED | AUTOMATED (host) | smoke: real install/launch | LOW | none |
| F-LIFE | lifecycle | Stage 8/16 evidence | session truth rule, FGS soak proven (lab) | SessionManager + FGS observation owner | Activity/Service/JNI | IMPLEMENTED | AUTOMATED + CI | soak: background survival | LOW | FGS approval for prod use |
| F-OBS | observation | Stages 9–14 (lab) | 55/57 frames, 3 chats + 51 travels | translator→source→consumer→snapshot | WebSocket lab (evidence only) | IMPLEMENTED | AUTOMATED + CI | ONE guided live session pending | LOW | live session = user-gated |
| F-GSTATE | game-state | Stage 7/10 | Availability-gated fields, never fabricated | GameState::unattached defaults | verified provider (none yet) | IMPLEMENTED | AUTOMATED | needs verified source | LOW | no source → stays unavailable |
| F-EVENT | events | lab + bus | typed bus, translator Unknown≠Invalid | EventBus + translator + consumer | none (host-testable) | IMPLEMENTED | AUTOMATED + CI | none | LOW | none |
| F-CONFIG | config | WClient/BedrockTools pattern | schema-versioned JSON store | config_store sections | files | IMPLEMENTED | AUTOMATED | none | LOW | none |
| F-PROFILE | profiles | multi-ref preset pattern | named presets incl. Recording; two-phase validated apply | profile_manager + profile_apply + app wiring | files/JNI read | IMPLEMENTED (Batch 5: presets + applier) | AUTOMATED (profile + profile_apply) | smoke: switch/persist | LOW | JNI create/reset/delete → Batch 6 |
| F-MODULE | modules | Lunar Proxy 101-cat, Flarial 140+ | descriptors + lifecycle + quarantine | module_manager + first real modules | none | IMPLEMENTED (Batch 1: 5 local modules registered) | AUTOMATED (manager + sources) | none | LOW | catalog entries: fps/cps/clock/session_stats/stop_watch PARTIAL |
| F-INPUT | input | Flarial/Lunar keystrokes+CPS | abstract touch routing, tap counter | input_router + tap/CPS source | touch host | IMPLEMENTED | AUTOMATED | smoke: touch routing | LOW | CPS window source (Batch 1) |
| F-UI | ui framework | Lunar/Flarial/Atlas ClickGUI | nav/search/favorites, honest toggles | gui_controller + ClickGUI model | overlay host | IMPLEMENTED | AUTOMATED | smoke: render/touch | LOW | overlay binding partial |
| F-HUD | hud framework | Lunar/Flarial/Atlas HUD | layouts/editor/theme, "--" for unverified | hud_model/renderer/theme + providers | frame clock (Batch 1) | IMPLEMENTED | AUTOMATED | smoke: on-screen render | LOW | data providers (Batch 1) |
| F-LOG | logging | — | redacted logs, diagnostics | crash_guard + redaction | files | IMPLEMENTED | AUTOMATED | none | LOW | none |
| F-ANIM | animation | multi-ref motion patterns (original curves) | unified easings + controller + reduced-motion | animation.h (fade/slide/scale/spring/stagger) | none (injected time) | IMPLEMENTED (Batch 3) | AUTOMATED (test_animation) | smoke: visual smoothness | LOW | consumers wire in later (startup/pages/toggles) |
| F-UPD | update/config arch | Lunar/Atlas | signed+checksum update checks | checker design (not built) | network (update host) | RESEARCH_REQUIRED | none | none | GUARDED | needs owner + signing keys |

## Tier 2 — Universal client features (reference-supported)

Rule: framework/config side implementable now; any widget needing game
data stays "--" until its source is verified (F-OBS covers exactly
PlayerMessage/PlayerTravelled; nothing else has a source).

| ID | Category | Reference evidence | Observed behavior | Xykell requirement | Required surface | Impl | Tests | Device-only | Sec | Deps/blockers |
|---|---|---|---|---|---|---|---|---|---|---|
| U-HUD-LOCAL | hud | Lunar/Flarial/Atlas | FPS/clock/session/CPS widgets | local-data providers (Batch 1) | frame clock, tap counter, device clock | IMPLEMENTED (Batch 1: hud_sources + 5 modules) | AUTOMATED (test_hud_sources, CI) | smoke: render | LOW | done; registry PARTIAL ×5 |
| U-HUD-GAME | hud | same | coords/armor/health/target HUD | providers bound to verified sources | game source (absent exc. travel/position events) | RESEARCH_REQUIRED | none | source first | LOW | no source; PlayerTravelled position is event-scoped, not continuous state |
| U-PERF | performance | Lunar/Atlas | FPS unlock, entity culling, profiler | per-version adapters + OS APIs | render hooks (native load path) | RESEARCH_REQUIRED | none | measured per-release | GUARDED | native provider LAB-GATED (Stage 8) |
| U-INPUT-DISP | input display | Flarial/Lunar/Nova | keystrokes/CPS overlay | tap stream → CPS provider | touch host | IMPLEMENTED (Batch 1: TapCounter; keystrokes element still needs tap-position stream) | AUTOMATED (test_hud_sources) | smoke: touch | LOW | keystrokes display → RESEARCH_REQUIRED |
| U-ZOOM | visual | Atlas/Flarial | FOV zoom | render/input hook | native load path | RESEARCH_REQUIRED | none | per-version sigs | GUARDED | native provider LAB-GATED |
| U-CHAT | social | multi-ref | timestamps/filtering | chat customize on observed messages | PlayerMessage source (proven) | RESEARCH_REQUIRED | none | live source | LOW | production live source absent |
| U-NOTIFY | ui | multi-ref | toasts | notifications.h exists | none | IMPLEMENTED | AUTOMATED | none | LOW | wiring to UI |
| U-THEME | ui | multi-ref | 2 built-ins + JSON | theme.h | files | IMPLEMENTED | AUTOMATED | none | LOW | editor UI (Tier 3) |
| U-WIDGET | hud | multi-ref | drag/scale/visibility | hud editor state machine | touch host | IMPLEMENTED | AUTOMATED | smoke: touch edit | LOW | on-screen verification |

## Tier 3 — UX (shell-first, largely buildable)

Main menu, settings (catalog: 14 declared settings w/ validation+
reset+import-checks, IMPLEMENTED Batch 2), module configuration, profile management, HUD/theme/
keybind editors (backend: IMPLEMENTED Batch 4 — 8 default actions,
conflict-safe binds, touch-code range), localization, onboarding, diagnostics, accessibility:
app shell + Fragments exist; editors bind to F-CONFIG/F-PROFILE/F-HUD
state machines. Status: SPECIFIED (shell present), device smoke required
for touch/render acceptance. No runtime data needed → no Tier-1 blocker.

## Tier 4 — Advanced systems (evidence-gated)

Cosmetics (original only — never Lunar/Flarial/Atlas copies), animations,
waypoints (needs continuous position — absent), world/entity info (no
source), additional observations (only via new verified envelopes),
performance tooling (needs native load path), overlay systems: all
RESEARCH_REQUIRED except cosmetics-design (SPECIFIED, local-only).
Combat/movement-advanced (KillAura/Fly/Speed/X-Ray/packet): EXPERIMENTAL
only under Advanced gate, OFF default, never silent; most need a packet/
entity surface that does not exist → RESEARCH_REQUIRED (never fake).

## Tier 5 — Release engineering

Crash handling (IMPLEMENTED), APK size tracking (10.2 MB post-Stage-20;
attribute growth per change), startup/memory/CPU/event-throughput
profiling (SPECIFIED), lifecycle soak (lab-PROVEN, production soak
pending), regression suite (AUTOMATED: host 27/27 + lab 58/58 + JVM
15/15 + CI), security audit per batch, release packaging (levipack
pipeline exists), privacy/terms (check PRIVACY.md at release),
reference parity audit (this matrix + registry audit scripts).

## Batch plan (vertical slices)

- Batch 1 (this stage): local-data HUD providers (FPS/clock/session/
  CPS) + first real modules bound to manager + tests → U-HUD-LOCAL,
  U-INPUT-DISP → AUTOMATED, matrix updated.
- Next: ONE guided Stage-20 live session (user-gated) → F-OBS live
  claim; then U-CHAT on proven PlayerMessage; then Tier-3 editor
  wiring; Tier-4 only with new verified sources.
