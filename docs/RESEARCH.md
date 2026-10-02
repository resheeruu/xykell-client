# Xykell Client — Phase 0 Research (2026-10-02)

Target: Minecraft Bedrock / MCPE, Android ARM64 first, touch first.
Method: web research 2026-10-02. No proprietary code copied.

## 1. Ecosystem summary

### Lunar Client (Moonsworth)
- Platform: Minecraft **Java Edition**, Windows/macOS/Linux. No Bedrock native client, no Android build.
- Features (public docs): 65–75+ mods — Keystrokes, Coordinates, Freelook, ToggleSprint/Sneak, ArmorStatus, FPS/boosted frames, Fullbright, Reach/Combo/PvP info, Waypoints, Replay Mod, cosmetics, Hosted Worlds (Java host with Bedrock cross-play join only).
- Implementation: closed-source Java modpack/launcher. Open repos are peripheral (Apollo server API MIT, mappings, forks).
- License: proprietary client; peripheral GitHub repos MIT/EPL/MPL/BSD.
- Mobile compat: none.
- Relevance to Xykell: **feature-category inspiration only** (HUD layout, mod menu UX, perf options). Nothing portable to Bedrock Android directly.
- Status for porting: [NOT-FEASIBLE] as code; [PLANNED] as independent re-implementation of QoL equivalents.

### W Client
- No authoritative current source found in this pass.
- Marked [REQUIRES-RESEARCH]. Do not speculate. Likely Java-scene name; treat as inspiration-only until verified.

### Nova Client (TeamNovaMC)
- Platform: Minecraft Bedrock via **MITM (Man-in-the-Middle) proxy**, primary platform Android; claims cross-platform control (Android/iOS/Windows/Switch/Xbox-limited/PS-limited).
- Approach: no game-memory modification, no root; packet-level interception + relay + Android app UI (Kotlin/Gradle + relay dir).
- Features: combat/motion/visual/particle/effects/misc modules, dual GUI (Classic/Nova/ClickGUI), live config, mobile-first UI.
- Open source: yes, **GPL-3.0** (github.com/TeamNovaMC/Nova-Client, ~32 stars, 78 commits at time of writing).
- Mobile compat: yes, Android-first.
- Relevance: proves MITM path works on Android without hooks; limited to **network-visible** features — cannot do FPS unlock, shaders/render, input overlay internals.
- Status: [PLANNED] as optional network-abstraction backend; GPL code must not be copied into MIT tree — clean-room only, keep license boundary.

### Atlas Client (Atlas Software LLC)
- Platform: **MCPE Android + iOS native**, Android 64-bit only; V2 on Google Play (free) + Premium early access (e.g. Minimap).
- Features (public): 60+ non-cheat QoL mods, FPS unlocker (breaks 60fps cap), shaders via MaterialBin packs, waypoints, minimap (premium), clean mod menu, version switcher (iOS).
- Implementation: closed/proprietary app; GitHub org exists but client is commercial.
- Versions: tracks latest Bedrock (site cites 26.44 at time of writing).
- License: proprietary / commercial terms.
- Relevance: closest commercial proof that native Android QoL client (FPS unlock, HUD, shaders) is shippable via Play Store distribution.
- Status: inspiration only; [REQUIRES-RESEARCH] for any interop; never copy assets/branding.

### Flarial Client (Flarial)
- Platform: **Bedrock Windows 10/11 x64 + Android (MCPE)**. 140+ free core QoL modules, ClickGUI, HUD (FPS/ping/armor/keystrokes/CPS/hotbar), FPS/render tools, launcher with version management + auto-inject on title screen.
- Implementation: Windows = client DLL injected into Bedrock (needs injector; launcher wraps it); Android = official APK/launcher (Play listing at times, otherwise official CDN APK). GDK runtime noted for builds after 1.21.120.
- Open source: **partial**. `flarialmc/dll-oss` (AGPL-3.0, ~205 stars) with delayed signatures/offsets (stays one version behind) and private portions withheld. Lua scripting wiki public.
- License: AGPL-3.0 for the OSS slice; official builds include closed parts.
- Mobile compat: yes, Android build in active development (1.2.x line mid-2026, supports 1.26.x).
- Relevance: strongest Bedrock-client reference architecture: launcher + injected native layer + module registry + ClickGUI + per-version signatures.
- Status: [PLANNED] to mirror the *pattern* (launcher + native module + version adapter), never the code/offsets.

### LeviLamina / LeviMC ecosystem
- **LeviLamina**: mod loader for Bedrock **Dedicated Server (BDS)**, C++, LGPL-3.0 (non-closed parts), 1.6k+ stars. C++ API + event bus, script engine for JS/Lua/Python. Server-side only — does not run inside the Android game client.
- **LeviLaunchroid ("LeviLauncher")**: open-source **Android launcher for Bedrock**, Java, Apache-2.0, ~540 stars. Imports official Play APK, launches without system install, multi-version isolation, Xbox account switching, world/pack manager. Requires Android 9+, ARM64, legitimate Play copy. Rejects MC < 1.21.80 (current policy).
- **preloader-android**: public native-mod SDK (C++, CMake FetchContent). Mods ship as `preload-native` .levipack: `manifest.json + lib*.so + config/`. Single `PL_REGISTER_MOD` lifecycle object, typed config, Mod Menu integration, hook/patch handles in mod-owned state. Build target **arm64-v8a**.
- Relevance: **the realistic Android-native path for Xykell**. Launcher (Java/Kotlin) + preloader .so (C++) + per-version signature adapter.
- Status: [PLANNED] as base architecture; Apache-2.0/LGPL compatible with care; pin SDK tags for reproducible builds.

### Apollon Client
- No authoritative source found in this pass. Marked [REQUIRES-RESEARCH]. Do not implement against it until verified.

## 2. Android Bedrock modding constraints (verified)
- Game binary: `libminecraftpe.so`, ARM64 (arm64-v8a) primary; also armeabi-v7a/x86/x86_64 builds exist per version codes (e.g. 1.26.23.1 builds 45295242/45295247).
- Official path = launcher + preloader hooks/patches (Levi pattern) or MITM proxy (Nova pattern) or packs/add-ons + Scripting API. No Fabric-style Java mods on Bedrock; no Windows-DLL-injection on Android (different loader, SELinux, Play integrity).
- ARM64 inline hooking is proven (ShadowHook/And64InlineHook/Dobby-class tooling) but fragile across OEMs/Android versions — needs per-device testing (Android 9–15+ spread).
- Mojang constraints: min specs now Android 9+, OpenGL ES 3.1+; updates can break signatures every release → VersionAdapter + graceful disable is mandatory.
- Legit copy required everywhere (Play license). Never ship game binaries, never bypass auth.

## 3. Architecture decision (recorded, pending user approval for implementation)
- **A (recommended): native preloader .so** via LeviLaunchroid/preloader-android pattern — full HUD/render/input, per-version signatures, legit-copy launcher. Cost: NDK/CMake, per-release upkeep.
- **B (fallback/parallel): MITM proxy** (Nova-style) — no memory edits, packet-only modules, easier cross-platform. Cannot do FPS/render/shader work.
- **C (always-safe): packs + scripting + overlay companion** — 100% TOS-safe QoL, survives version churn, limited depth.
- Xykell v1: A for core+perf+FPS/CPS/coords/keystrokes+ClickGUI; B isolated under Advanced>Network experiments; C as graceful-degraded mode. Details in docs/ARCHITECTURE.md.

## 4. Depth pass 2026-10-02 (master-spec sources)

### Lunar Proxy (lunarproxy.net) — closed commercial proxy, Bedrock yes, Android yes
Proxy (not a mod): runs on phone/PC, consoles join through it. 101 modules incl. KillAura, Reach, HitBox, TriggerBot, AutoCrystal, AnchorAura, Fly, Xray, ChunkFinder, FreeCam + in-game ClickGUI + per-module pages. Mobile free w/ ads, PC subscription. Proves PACKET-class depth without native hooks. Reuse: catalog inspiration only.

### WClient (RetrivedMods/WClient) — GPL-3.0, legacy archive, Bedrock yes, Android primary
Modular packet-level client (no direct game-memory modification), MITM-style cross-platform reach; categories Combat/Motion/Visual/Misc; JSON runtime config. Public dev now closed, repo is an archive. Reuse: behavior ideas only, GPL boundary.

### BedrockTools (QYCottage) — primary native reference, Bedrock yes, Android yes
Open-source LeviLauncher native mod: C++20, xmake, NDK r28c, 36 modules (Visual/HUD/Player/Misc incl. FPS Unlocker, Zoom, Fullbright, Ping/Reach/Combo HUDs), public SDK headers, typed runtime event system, mod-menu integration, persistent config, version-specific sigs/offsets, `.levipack` distribution. License: README says GPL-3.0 (badge says MIT — discrepancy, treat as GPL-3.0). Reuse: shape reference only, no code pasted.

### Apollon Client — closed cheat APK, Bedrock yes, Android yes
Standalone wrapped client (movement/combat/position tools, pinnable touch UI). No authoritative public source; third-party mirrors only. Status: [REQUIRES-RESEARCH] for source; inspiration limited to touch-UI pinning concepts. Never its binaries.

### Bonus references found
Xelo-Client (GPL-3.0, launcher-based modules, Material You UI, shader support), ModdedBE (NMod launcher), Selaura (scripting + multiversion). Same GPL/behavior-only boundaries.

## 5. Open items
- W Client + Apollon Client verification [REQUIRES-RESEARCH].
- Confirm current Bedrock protocol/latest version at build time (churns monthly; re-check before native work).
- Confirm preloader-android latest tag + LeviLaunchroid Gradle/NDK baseline on this machine before writing C++.
