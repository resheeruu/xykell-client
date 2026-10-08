# XYKELL Reference Parity (behavioral comparison, not copying)

References are behavioral/product research only. Nothing proprietary is
reproduced. Statuses: RESEARCHED, SPECIFIED, IMPLEMENTED, AUTOMATED,
CI-VALIDATED, DEVICE-VALIDATED, RELEASE-READY, UNAVAILABLE,
RESEARCH_REQUIRED. Unsafe capabilities are REFERENCE-ONLY / NOT
IMPLEMENTED, never reproduced.

## Flarial (140+ QoL, best Bedrock pattern ref; AGPL dll-oss slice)

| Feature | Observed behavior | Evidence | Xykell interpretation | Implementation | Tests/CI | Device | Status |
|---|---|---|---|---|---|---|---|
| HUD modules (FPS/coords/CPS/clock) | overlay widgets + editor | modules page + AGPL slice | local-data providers first, game data only when sourced | hud_sources (FPS/CPS/clock/session) | test_hud_sources/host | smoke render | IMPLEMENTED |
| ClickGUI (search/fav/toggles) | touch module browser | modules page | gui_controller + ClickGUI model | native GUI stack | host suites | smoke touch | IMPLEMENTED |
| Keystrokes/CPS overlay | input display | modules page | tap stream → CPS (positions need stream) | TapCounter; keystrokes display pending | host | smoke touch | SPECIFIED |
| Zoom, Fullbright, Freelook | FOV/light/camera prefs | modules page | legitimate prefs where technically possible | Fullbright + time_changer implemented as SetTime 0x0A day-cycle rewrites (VisualModules); zoom is camera FOV, no packet field; freelook needs the server's prior body yaw, so cross-packet | host VisualModulesTest (15 tests) | device relay run pending | Fullbright/time_changer PARTIAL; zoom/freelook NOT DELIVERABLE BY A RELAY |
| Waypoints, compass | world markers | modules page | position now observed (PlayerTravelled); marker readout = distance/bearing on waypoint rows | WaypointStore + MarkerMath + WaypointsFragment | host MarkerMathTest | source first (in-game anchor) | PARTIAL (in-game anchor device-gated) |
| Scripts/automation | sandboxed scripting | modules page | sandboxed scripts (engine-limited) | ScriptEngine + sandbox + API (host-tested) | host suites | device sandbox review | PARTIAL |
| Combat automation | target/attack automation | modules page | Assessed id by id rather than excluded wholesale: a stateless packet rewrite of bytes the relay already terminates is in scope; input injection and target selection are not | velocity (drops clientbound SetEntityMotion 0x28); the other 29 COMBAT ids carry a per-id reason | host CombatModulesTest (25 tests) + ModuleRuntimeTest (15) | device run pending | velocity PARTIAL; remaining 29 assessed not-deliverable |

## Lunar Proxy (101-module catalog, closed commercial)

| Feature | Observed behavior | Evidence | Xykell interpretation | Implementation | Tests/CI | Device | Status |
|---|---|---|---|---|---|---|---|
| Module catalog breadth (101) | category-organized modules | authoritative catalog 2026-10-02 | registry taxonomy source | registry 258 entries | validate.py | — | RESEARCHED |
| Target HUD, armor/potion HUD | game-state widgets | catalog | providers when sourced | hud_model types exist, "--" | host | source first | RESEARCH_REQUIRED |
| KillAura/combat automation | automated combat | catalog | EXCLUDED | never | — | — | REFERENCE-ONLY / NOT IMPLEMENTED |
| Packet modules | packet monitor/control | catalog | monitor RESEARCH; control EXCLUDED | none | none | no packet API | RESEARCH_REQUIRED |

## Lunar (Java client inspiration)

| Feature | Observed behavior | Evidence | Xykell interpretation | Implementation | Tests/CI | Device | Status |
|---|---|---|---|---|---|---|---|
| Performance dashboard | FPS/memory/CPU/graphs | public docs | OS+frame-timer profiler | SPECIFIED (not built) | none yet | measured | RESEARCHED |
| Cosmetics (own identity) | capes/particles/emotes | public docs | original Xykell cosmetics only | none yet | none | — | SPECIFIED |
| Friends/social | lists/presence | public docs | local friends list exists; backend-gated rest | FriendStore + FriendsFragment + friends.h | FriendStoreTest 14 | BACKEND_REQUIRED for online | IMPLEMENTED (local; presence not claimed) |

## Atlas (60+ QoL, closed)

| Feature | Observed behavior | Evidence | Xykell interpretation | Implementation | Tests/CI | Device | Status |
|---|---|---|---|---|---|---|---|
| FPS unlock, shaders, minimap | perf/visual/world QoL | site + Play claims | per-version adapters; minimap needs chunk data (absent) | none yet | none | measured/sourced | RESEARCH_REQUIRED |
| Profiles/modes | preset switching | site | profile_manager presets | native + Default shell | host | smoke switch | IMPLEMENTED |

## WClient (GPL-3.0 legacy archive, license boundary kept)

| Feature | Observed behavior | Evidence | Xykell interpretation | Implementation | Tests/CI | Device | Status |
|---|---|---|---|---|---|---|---|
| JSON config | file-based config | archive behavior | config_store schema | native | host | — | IMPLEMENTED |
| Packet MITM | proxy interception | archive behavior | Shipped as the app's own relay, not as packet inspection: RelaySession terminates both Bedrock legs and forwards plaintext, so the client's identity is re-signed rather than any traffic being read out | RelayService + RelayPipe + RakNetEndpoint + BedrockBatch + BedrockHandshake + BedrockIdentity + RelayListener | host test_relaysession (4), test_raknetendpoint, test_bedrockbatch, test_bedrockhandshake | on-device E2E against a real server pending (MIUI blocks CLI install) | IMPLEMENTED (proxy.mode / proxy.relay PARTIAL; observation-only, nothing logged to disk) |

## LeviLauncher (Apache-2.0 OSS, APK verified on-device)

| Feature | Observed behavior | Evidence | Xykell interpretation | Implementation | Tests/CI | Device | Status |
|---|---|---|---|---|---|---|---|
| Launcher primitives | import/isolate/accounts/worlds | OSS + device APK | staged PLAY pipeline, honest blockers | LaunchExecutor/Decider | host | smoke launch | IMPLEMENTED |
| Preloader SDK | native module loading | OSS | LEGACY seam via portal/ (zero-Levi end-state) | portal/ seams | host | load path | SPECIFIED |

## Ambient / Lumina (weak static-only evidence)

Ambient (Yurai dlopen/hook + gamecores): INFERRED only — no
capability reproduced; injection-class mechanisms EXCLUDED by policy.
Status: RESEARCH_REQUIRED (black-box only). Lumina (local relay +
codecs + LAN adv): relay concepts inform lan_discovery metadata-only
design; codecs/LAN-adv beyond metadata: RESEARCH_REQUIRED.

## Unsafe-capability register (never implement)

Anti-cheat bypass, anti-ban, credential/token theft, auth/DRM/license
bypass, server exploitation, packet spoofing to evade rules, combat/
movement cheats, remote command/control, hidden remote execution,
arbitrary server manipulation: REFERENCE-ONLY / NOT IMPLEMENTED.
Full-feature audit (`scripts/audit/full-feature-audit.py`) enforces
no fake SUPPORTED entries.
