# Injection architecture — how Bedrock clients actually work

**Status: research. Nothing described here is implemented in this repository.**

This document records how Atlas, Flarial, LeviLauncher and related Android
Bedrock clients achieve in-game modification, and why the relay this project
built cannot deliver those features. It exists because four releases shipped
fixes to a subsystem that was architecturally incapable of doing the job.

## WClient — the counter-example that corrects this document

Earlier drafts of this document claimed the relay architecture could not
deliver the combat, motion and visual features. **That was too strong, and
WClient disproves it.**

WClient's own description:

> "WClient does not modify game memory directly and is engineered for
> compatibility across multiple Bedrock environments." — working "at the packet
> level instead".

> "primarily developed and tested for Android, but can interface with other
> platforms using **MITM-style packet interception** depending on setup".

Its archived GPLv3 repository has this top-level layout:

```
app/
gradle/
relay/
```

There is a `relay/` directory. And it ships, per its distribution pages:
Kill Aura (auto-attack to 50 blocks at 25+ CPS), Motion Fly, Player TP, server
bypasses, CPS counters, and an ArrayList overlay — **all without touching game
memory.**

So the architecture this project already built is validated by a shipping
client. The relay is not the wrong idea.

### What the correction actually is

| Claim | Verdict |
|---|---|
| The relay cannot deliver combat/motion features | **Wrong.** WClient does exactly this. |
| A packet hook cannot be an ESP or an autoclicker | **Partly right, and narrower than stated.** Packet-originated actions (auto-attack, motion, inventory) work. What a relay genuinely cannot produce is anything requiring a **render pass into the game's framebuffer** — a world-space ESP box, an in-game ClickGUI — because those are drawn by the game, not carried in packets. |

This agrees with what `docs/PROXY-DESIGN.md` concluded before the fact: 11 ids
are "genuinely deliverable from a relay", and the remainder "need input
injection, a render/overlay pass, inventory or world state the relay never
decodes".

The real reason nothing works is therefore narrower and less flattering than
"wrong architecture": **the relay has never completed a single live Bedrock
session.** Every failure to date is upstream of that — packaging bugs, a stuck
splash overlay, and a design that was never exercised against a real server.
No amount of reading architecture docs substitutes for that run.



Every reference client works by running **native code inside the Minecraft
game process**. None of them modify gameplay by inspecting network traffic.

| Client | Mechanism |
|---|---|
| LeviLauncher | "Preloader APIs to load native SO modules, receive input callbacks, install hooks, and apply patches." "Native modules run native code in the game process." |
| Flarial | "Launches Minecraft & injects the specified DLL." The launcher "waits for Minecraft to load, then injects the client at the title screen." |
| Atlas | Non-cheat QoL mods shipped as an app that launches the game with the mod layer active. |

Our repo already knew this and said so:

- `docs/PROXY-DESIGN.md:187` — "A packet hook cannot be an ESP overlay or an
  auto-clicker; delivering those would need the injection/render architecture,
  not a registry flip."
- `docs/LAUNCHER-INTEGRATION.md` — "The loader stage has no verified mechanism,
  so PLAY always ends `STANDALONE RUNTIME NOT READY`."
- `docs/RUNTIME-SUBSTRATE.md` — "Synthetic Relay ≠ Minecraft protocol
  implementation."
- `XykellInfo.kt` carries `LEVI_TARGET` and `PRELOADER_PIN`, reserving the seam
  for a preloader that was never built.

## Why the relay cannot deliver the features

A relay sees packets in flight. The features need process memory and a render
pass.

| Feature class | Needs | Relay |
|---|---|---|
| afk_clicker, double_click, quick_drop | Input injected into the game's input handler | No |
| backtrack | Game state correlated across packets | No |
| tab_list, server_info, ip_display, death_position | Live game state + a render pass | No |

Even the packet-derived subset requires the game to route through the relay
via a manual local-server entry — a path that has never worked on the test
device, and which still would not produce an ESP, an autoclicker, or an
in-game HUD.

## The technique, in detail

`libminecraftpe.so` holds nearly all game logic: tick, render, network, UI,
weather, time, skins. It is **stripped**, so there are no symbols to call —
every target must be located by a **byte signature** verified to be unique on
that exact build.

### Hook kinds

1. **Inline hook** — patch the function prologue, jump to a trampoline that
   runs our code and returns to the original. Used for ordinary logic
   (`SetTime`, `NormalTick`, `SurvivalModeAttack`).
2. **Vtable hook** — replace a C++ virtual dispatch slot. Used when an object
   layout is stable but the concrete type varies.
3. **Library-import hook** — patch a symbol we know the game imports. This is
   the render path (below).

Hook frameworks in common use: **ShadowHook** (ByteDance), **Dobby**, or a
small local ARM64 inline hooker. ShadowHook is generally preferred on ARM64
Android.

### The render pass

The ClickGUI and HUD draw by hooking **`eglSwapBuffers`** in `libEGL.so`:

```c
auto egl = openLibrary("libEGL.so");
auto address = symbol(egl, "eglSwapBuffers");
install((void*)address, (void*)swapBuffersDetour, (void**)&swapBuffersOriginal);
```

The detour queries the surface size, initialises the ImGui context, draws the
menu, then calls the original. This renders on top of the game without
touching the game's own rendering code.

### Loading the mod

Two patterns are in production use.

**Hosted launcher (no root, no modified APK).** Proven by the KafkaLauncher
proof-of-concept:

1. The launcher hosts Minecraft's `MainActivity` in its own `:minecraft` process.
2. Minecraft's real Java Activity is constructed through Minecraft's class loader.
3. The launcher's APK ships its **own** `libminecraftpe.so` — a small forwarder —
   which Android loads because `NativeActivity` resolves the library named by
   `android.app.lib_name = minecraftpe`.
4. The forwarder scans `/proc/self/maps` for the **already-mapped real**
   `libminecraftpe.so`, ignoring its own mapping.
5. Hooks are installed into the real library.
6. The forwarder calls the real `ANativeActivity_onCreate`; the game boots
   normally, now inside the hooked process.

Alternative trigger: hook `dlopen` itself and act when the game loads
`libminecraftpe.so`.

**External injector.** A separate app attaches to the running game process and
`dlopen`s the mod. Requires the process to be attachable, which on stock
Android generally needs the game to be debuggable, an elevated injector, or a
rooted device.

### Version coupling

Hook addresses are **version-specific**. KafkaLauncher states it supports only
one Minecraft version (1.21.51.02) arm64-v8a, and that other versions "may boot
but should be treated as unsupported." Every Minecraft update invalidates the
signature dictionary. This is the dominant ongoing cost of this architecture,
not a one-time build.

## What this means for Xykell

The native core (`xykellcore`) already compiles for arm64-v8a and the C++ HUD
renderer is real. The missing pieces are:

1. A **loader** — forwarder `libminecraftpe.so` plus process hosting, or an
   injector.
2. A **signature dictionary** for the target Minecraft version, verified unique.
3. A **hook layer** (ShadowHook or a local ARM64 inline hooker).
4. An **`eglSwapBuffers` render pass** with ImGui for the in-game menu and HUD.
5. An **input callback path** for the gesture features.

The existing relay is not wasted — it remains useful for packet-monitor and
packet-logger style features, which genuinely are relay-appropriate. It should
be reclassified accordingly rather than treated as the delivery mechanism for
all 258 registry ids.

## Risk

Injecting into the game process and modifying gameplay violates the Minecraft
EULA and risks a permanent ban on the account used. Some reference clients
deliberately ship only non-cheat modules for this reason (Atlas markets "no
cheats, ever"). That risk was accepted for this project before any of it was
built.
