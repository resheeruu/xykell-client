# Levi integration (foundation spec)

Xykell runs **on top of LeviLaunchroid**, not beside it. Do not reimplement launcher, version management, preloader, or Mod Menu hosting unless research proves a required capability is missing.

## 1. Stack
Xykell Android/UI Layer (`app/` launcher shell, M1.5) → Xykell Native Core (`native/`) → Xykell Module Engine → **LeviLaunchroid Preloader** → Minecraft Bedrock Android (arm64-v8a, Android 9+, legitimate Play copy).

Boundary: Xykell never reimplements Levi's launch/version/isolation machinery.
M1.5's PLAY button stays NOT WIRED until the real Levi launch path (intents,
package, file locations) is researched and verified — nothing invented.

## 2. What Levi provides (verified from LeviLaunchroid docs, 2026-10-02)
- APK import + installation-free launch, multi-version management with full data isolation.
- Multiple Xbox account management + switching; world/resource-pack manager with import/export/backup.
- Native `.so` module loading via preloader (`preload-native` packs: `manifest.json` with `type/name/author/version/entry/minecraft_versions`, plus `lib*.so` + `config/config.json` + `config.schema.json`; directory name = stable mod id).
- Native Mod SDK (`LiteLDev/preloader-android`, CMake FetchContent, pin `GIT_TAG`): one long-lived object per mod via `PL_REGISTER_MOD`, typed config, Mod Menu integration, hook/patch handles as mod-owned state, input callbacks, patch APIs.
- Reference implementation: `examples/full-cpp-mod` → `.levipack` output; build target **arm64-v8a**.

## 3. What Xykell adds (clean layer, no Levi fork)
- `libxykell-core.so`: registry, lifecycle, typed event bus, JSON config + profiles, keybind/touchbind, theme, VersionAdapter, redacted logging/crash/diagnostics, render/input/network abstractions.
- Feature modules as separate preloader packs (or core-linked in M1 proof) declaring Xykell capability classes (see MODULE-SYSTEM.md).
- Touch-first HUD/ClickGUI rendered through core's overlay abstraction; Mod Menu registration via Levi's integration point.
- VersionAdapter + CompatibilityManager: per-build support tables in `client/compatibility/`; unsupported → clean refusal, never attach blind.

## 4. Primary architecture reference: BedrockTools (QYCottage)
Open-source LeviLauncher native mod (C++20, xmake, NDK r28c, 36 modules: Visual/HUD/Player/Misc), public SDK headers (`include/bedrocktools`), typed runtime event system other mods can subscribe to, mod-menu integration, persistent config, version-specific signatures/offsets. Xykell mirrors this *shape* (core runtime + categorized modules + public event headers) with original code. License: README states GPL-3.0 — keep a license boundary; do not paste BedrockTools code into Xykell's MIT tree (see LICENSES.md).

## 5. Gaps / [RESEARCH REQUIRED]
- Exact preloader-android release tag to pin (check at Task 0).
- Input-callback and render-hook entry signatures for the current Bedrock build (derive through our own pipeline; never copy offsets).
- LeviLaunchroid minimum-supported-Minecraft policy drift (rejects < 1.21.80 at time of writing).
- Whether Levi Mod Menu exposes everything Xykell ClickGUI needs, or a separate overlay is required (M1 proof decides).

## 6. Rules
- Prefer Levi APIs over custom hooks for lifecycle/input/config/menu.
- Custom hooks only where Levi offers no path, documented with reason + version scope.
- Never bypass auth/DRM/anti-cheat/platform security; never redistribute game binaries.

## 7. Batch 5 findings (release-notes + docs evidence)
- Version lines: v1.5.17 → MC 1.26.45; v1.5.24 → MC 1.26.50 (device 1.26.45.1 covered).
- Quick Launch = Minecraft URI actions (open screens/connect/Realms/worlds/commands) —
  the verified lead for Xykell's future PLAY wiring (exact action list TBD via device/Levi source).
- External mods catalog V2 (mod downloads), HUD Editor with snapping, inbuilt
  AutoSprint/QuickDrop, foreground service, gyro controls — Levi-side features
  Xykell must NOT duplicate (integrate, don't reimplement).
