# Module system

## 1. Capability classes
Every module declares one or more: `NATIVE` `PACKET` `RENDER` `INPUT` `UI` `WORLD` `SCRIPT` `HYBRID`.
Examples: Zoom → NATIVE+RENDER+INPUT. CPS counter → INPUT+UI(HUD). Packet monitor → PACKET+UI. Waypoints → WORLD+RENDER+UI. Script module → SCRIPT.

## 2. Module manifest (all fields required)
`id` (stable, matches pack dir), `name`, `category`, `description`, `minecraft_versions`, `capabilities[]`, `dependencies[]` (ids + min versions), `settings` (typed schema), `default_state` (OFF for ADVANCED/EXPERIMENTAL, always), `keybind`, `touchbind`, `compatibility` (SUPPORTED/PARTIAL/UNSUPPORTED/BLOCKED/RESEARCH_REQUIRED + reason).

## 3. Module API (stable; hides Minecraft internals)
`onLoad()` `onEnable()` `onDisable()` `onTick()` `onRender()` `onInput()` `onWorldChange()` `onPacket()` `onShutdown()`.
Modules talk to the game only through core abstractions (render/input/network/version), never raw unstable structs. VersionAdapter gates every callback: unsupported build → module stays disabled with status shown in ClickGUI + launch report (`XRay UNSUPPORTED / Zoom SUPPORTED / …`).

## 4. Lifecycle & isolation
Load → version check → dependency check → enable; any fault → quarantine (id + reason persisted), event-bus dispatch continues, safe mode offered when faults repeat. Disabling a module unsubscribes it fully (bus, hooks, overlay) and must be survivable mid-session — proven in M1.
Runtime: `ModuleManager` (register/unregister/enable/quarantine, duplicate + empty-id rejection) + `EventBus` (throw-proof dispatch, per-module failure counts, snapshot iteration). Crash persistence: `CrashGuard` (counts, threshold-3 auto-quarantine, safe-mode flag + reason, last-known-good). See docs/CRASH-GUARD.md.

## 5. Dependency handling
Missing/outdated dependency → module stays disabled with message naming the dependency. No auto-download of code; updates are signed packs with checksums.

## 6. Settings
Typed, schema-versioned, profile-scoped overrides. Import/export JSON. Reset per-module and global.
