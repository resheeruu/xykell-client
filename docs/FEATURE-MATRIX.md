# Xykell — Feature Matrix

Machine truth moved to `registry/features.json` (225 entries, v2 ids
`xykell.<category>.<suffix>`, 15 categories) + generated
`docs/MODULE-CATALOG.md`. The tables below are the Phase-0 baseline, kept
for history; do not extend them — extend the registry.

Legend: [IMPLEMENTED] [PLANNED] [EXPERIMENTAL] [PLATFORM-LIMITED] [NOT-FEASIBLE] [REQUIRES-RESEARCH] [BLOCKED — PLATFORM LIMITATION]

> Nothing below is [IMPLEMENTED] yet — repo is at Phase 0/1. This table is the tracking baseline.

## Cross-source capability map (master spec §32 + capability classes)

| Feature | Source Inspiration | Implementation | Capability | Android | Version | Status |
|---|---|---|---|---|---|---|
| Zoom | Atlas/Flarial | Native | RENDER+INPUT | Yes | Adapter | Planned |
| FPS HUD | Atlas/Flarial/BedrockTools | Native | UI(HUD) | Yes | Stable | Planned (M1 proof: ON) |
| Coordinates HUD | Flarial/BedrockTools | Native | UI(HUD) | Yes | Stable | Planned (M1 proof: ON) |
| Waypoints | Atlas/Flarial | Native | WORLD+RENDER+UI | Yes | Adapter | Planned |
| Packet Monitor | WClient/Lunar Proxy | Packet | PACKET+UI | TBD | Adapter | Research |
| KillAura-class combat | Lunar Proxy/Apollon | TBD (ADVANCED-gated) | HYBRID | TBD | Adapter | Research (never silent, OFF default) |
| Fly/Speed movement | Lunar Proxy/Apollon/WClient | TBD (EXPERIMENTAL) | NATIVE or PACKET | TBD | Adapter | Research |
| X-Ray/ore viz | Lunar Proxy/Apollon/Flarial | TBD (EXPERIMENTAL) | RENDER+WORLD | TBD | Adapter | Research |
| Scripts | Flarial/BedrockTools | Script (sandboxed) | SCRIPT | Planned | N/A | Planned (post-M1) |
| CPS/Keystrokes | Flarial/Lunar/Nova | Native | INPUT+UI | Yes | Stable | Planned (M2) |
| FPS Unlocker | Atlas/BedrockTools | Native hook | NATIVE+RENDER | Yes | Adapter | Planned (M4, measured) |
| Native loading | LeviLaunchroid/BedrockTools | Levi Preloader | NATIVE | Yes | Levi | Foundation (M1) |
| ClickGUI | Lunar Proxy/Flarial/Atlas | Native overlay | UI+INPUT | Yes | Stable | Planned (M2 skeleton; M1 minimal list UI) |
| Config/profiles JSON | WClient/BedrockTools | Native | UI | Yes | N/A | Planned (M1: Default only) |

## Performance
| Feature | Source inspiration | Category | Platform | Implementation | Status | Deps | Version limits | Notes |
|---|---|---|---|---|---|---|---|---|
| FPS display | Lunar/Flarial/Atlas | performance | Android | native overlay | [PLANNED] | core+render | per-version adapter | milestone 1 |
| FPS limiter / unlock | Atlas/Flarial | performance | Android | native render hook | [PLATFORM-LIMITED] | VersionAdapter | breaks often | needs per-release sigs |
| Dynamic/background FPS | Lunar/Flarial | performance | Android | native | [PLANNED] | core | — | — |
| Render/sim distance presets | Lunar/Flarial | performance | Android | game settings hook or settings API | [PLANNED] | compat | version-gated | graceful disable |
| Entity/culling/particle/anim/cloud/weather/fog/block/chunk opts | Lunar/Flarial | performance | Android | native hooks | [PLANNED] | profiler | per-version | profiler first |
| Memory/CPU/GPU + frame-time + graph + profiler | Lunar | performance | Android | OS APIs + frame timers | [PLANNED] | core | device-gated | low-end/balanced/custom presets |
| Auto device profiling + recommended settings | Lunar/Atlas | performance | Android | PerformanceManager | [PLANNED] | telemetry(local) | — | milestone 1 |

## HUD (all: pos/size/scale/opacity/color/font/bg/border/shadow/align/spacing/visibility/anim/profile)
FPS/CPS/keystrokes/coords/direction/ping/server/memory/clock/session/armor/durability/potions/health/hunger/item-count/cooldown/speed/velocity/combo/target/kills/death-pos/biome/TPS/custom-text/image/notifications — inspiration Lunar/Flarial/Atlas/Nova — Android native overlay — [PLANNED] (FPS/CPS/coords/keystrokes in milestone 1, rest after) — drag-drop editor + presets [PLANNED].

## Visual
Zoom/Fullbright/Freelook/perspective/FOV/crosshair editor/view-model/no-hurt-cam/camera/fog/block-outline/nametag — [PLANNED] (native hooks, version-gated). Tracers/ESP (item/player/mob/container/block)/waypoints/markers/compass/minimap-arch/shader-material integration — [EXPERIMENTAL] or [PLATFORM-LIMITED]; minimap + shaders via MaterialBin-style packs where native blocked. X-Ray/ore viz — [EXPERIMENTAL], OFF by default, server-rule risk noted.

## Combat/PvP
Legit/QoL (CPS/keystrokes/reach/hit/damage/combo/target-HUD/armor/potion/cooldown/weapon/projectile/crosshair/hitbox-viz) — [PLANNED]. Advanced (target selector/priority/rotation/attack-controller/crit-automation/auto-weapon/timing/strafe/targeting/filters/team-detect/invis-filter/anti-bot) — [EXPERIMENTAL], separate AdvancedCombat category, disabled by default, never silent.

## Movement
Legit (toggle sprint/sneak, status, HUD, speed/jump stats) — [PLANNED]. Advanced (Speed/Fly/Glide/Spider/Step/HighJump/NoFall/air-move/Bhop/packet/timer/velocity) — [EXPERIMENTAL] under Advanced>Movement, never auto.

## World
Coords/compass/biome/waypoints/minimap-arch/block/entity info/death/spawn/markers/container/structure — [PLANNED] (where accessible). AutoMine/TreeCapitator — framework [PLANNED], OFF default. Nuker/Scaffold — [EXPERIMENTAL], OFF default.

## Player/Utility
AutoSprint/Sneak/Eat/Tool/Fish, inventory counters, durability/low-HP/low-hunger warnings, anti-AFK, pickup/container info, quick actions, screenshots [PARTIAL], replay integration, session/playtime/death stats — [PLANNED]; replay/recording [PLATFORM-LIMITED].

## Network
Ping/status/reconnect/server-info/packet-monitor/diagnostics/packet-log(dev)/latency-graph/quality — [PLANNED] where legal. Advanced network experiments isolated [EXPERIMENTAL]. Out of scope (never): credential theft, auth bypass, server compromise, DoS, packet attacks, account theft, protection bypasses.

## Social
Friends/highlight/local nicknames/party-UI/chat-customize/timestamps/filtering/screenshot-share [PARTIAL]/Discord-RPC/streamer+privacy — [PLANNED]; RPC [PLATFORM-LIMITED] on mobile.

## Cosmetics
Original Xykell capes/wings/particles/emotes/badges/animated/profiles/local-only/optional-online-service — [PLANNED]. No Lunar/Flarial/Atlas copies.

## Profiles/Modes/UI/Compat
Profiles (Default/PvP/Survival/Performance/Recording/Low-End/Touch/Advanced; switching/per-world/per-server/import-export/backup/reset) — [PLANNED]. Modes SAFE(default)/QOL/ADVANCED/EXPERIMENTAL with warning gate — [PLANNED]. Touch-first Mobile UI + ClickGUI (search/fav/recent/descriptions/sliders/toggles/dropdowns/color/keybind+touchbind/profile/import-export/reset) — [PLANNED]. VersionAdapter (detect/warn/graceful-disable) — [PLANNED], milestone 1.

## Cross-cutting
Update checking (signed+checksum) [PLANNED]; crash handling + redacted logs + diagnostics [PLANNED] milestone 1; tests per Phase 19 [PLANNED]; docs/changelog per feature [PLANNED].
