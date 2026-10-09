# What's new

Covers the recent delivery batches: the HUD overlay, the relay→HUD observation
hand-off, the input-gesture and authored-packet work, and the packet decoders.
Status words mean what the registry says they mean — nothing here is
`SUPPORTED`, because that word requires a live server round-trip and no session
has run yet.

## Registry at a glance

| Status | Count | Meaning |
|---|---|---|
| PARTIAL | 124 | Built and host-tested. Never run against a live session. |
| REFERENCE_ONLY | 97 | Mapped and researched; blocked on a stated reason. |
| NOT_IMPLEMENTED | 22 | Understood, not written. |
| DEVICE_LIMITED | 15 | Needs a device or a specific platform to work. |
| SUPPORTED | 0 | Deliberately. It means "verified live". |

## The HUD is drawn over the game now

Until this batch, **no HUD value had ever been visible in a session.**
`HudPreview` was structural text inside the launcher; there was no overlay
permission, no overlay window, and no JNI call returning rendered lines. The
native HUD renderer existed but only in the game-side library, behind a portal
backend the app does not use.

- `HudOverlayService` paints a `TYPE_APPLICATION_OVERLAY` canvas at 4 Hz.
- `NativeHud.renderHudLines` renders **through the same tested C++ renderer**
  the game-side overlay uses and returns the lines as JSON, so the text drawn is
  the text `test_motion_hud` already pins.
- Started and stopped by hand from the HUD screen. The special
  `SYSTEM_ALERT_WINDOW` grant is requested by sending the user to system
  settings and then re-reading the real answer on resume — never assumed.
- Honest by construction: providers read the observation consumer, and a value
  the relay never observed renders `--`, not a zero.

**Scope:** this adds draw calls *above* the game. It cannot change what the game
renders. Renderer-side ids (`xray`, `gui_scale`, shaders, `free_cam`, zoom,
motion blur, crosshair replacement, view model) still need a hook inside the
game process.

## The relay's own decode now reaches the HUD

A real gap, and the single change that made the rest possible: the observation
consumer was only ever fed by `ObservationService` from the external localhost
feed. Everything the **relay** decoded stayed invisible no matter how correct it
was. `ModuleRuntime` now hands decoded changes to the sink through injected
callbacks — injected because the production sink crosses JNI and a host JVM test
cannot load it.

## Delivered ids

| Id | What it does |
|---|---|
| `combat.backtrack` | Rewrites clientbound `MovePlayer 0x13` to a position the entity held N ticks ago. |
| `combat.afk_clicker` | Tap cadence at a configured point. |
| `combat.double_click` | Two taps — the touch-layout double click. |
| `misc.quick_drop` | One long press on a hotbar slot. |
| `hud.tab_list` | Online roster from `PlayerList 0x3f`. |
| `hud.server_info` | Upstream host + protocol version from the relay's own handshake. |
| `hud.ip_display` | `host:port` of the live connection. |
| `player.death_position` | Death cause and where the player was, from `DeathInfo 0xbd`. |
| `player.spam` | A configured chat line on a cadence — the first **authored** packet. |

### Capabilities added to unblock them

- **Injected monotonic clock** on `ModuleContext` (`System.nanoTime`, not a wall
  clock that can jump). `ctx.tick` counts packets, not seconds: a still session
  still receives packets and a busy one does not tick in real time.
- **Bounded per-entity position history** (20 samples) on `EntityTable`, dropped
  with the entity on eviction.
- **`MacroStep.holdMs`** — "wait then tap" and "press for N ms" are different
  instructions, and overloading one delay field would have made them the same
  number. `MacroStore` encodes the hold and still decodes the old 3-field format
  as a plain tap.

## The first authored packet, and what it deliberately is not

`player.spam` is the first id the relay **authors** rather than rewrites. Scope is
narrow on purpose:

- the user's own account says text the **user configured**;
- the layout mirrors `BedrockPackets.text()`, the decoder this repo already
  verifies, and a round-trip test pins encoder against decoder. That test caught
  a real bug immediately — a missing trailing flag byte that would have made
  every packet rejected;
- a wall-clock cadence with a **hard 1 s floor** that is not a setting;
- fired on the outbound leg, so nothing is sent while the session is idle;
- the client's own packet still goes out first.

Forging a UseItem, an Interact or a placement action remains out of scope, and
each of those keeps its reason.

## Two corrections to earlier planning

Both were mine, and both changed the plan's shape:

1. **Sub-chunk palettes do not carry block names.** `LevelChunk.payload` is
   opaque `ByteArray` and `UpdateBlock` carries a *numeric* `block_runtime_id`.
   The id→name mapping lives in the game's resource pack, never on the wire, so
   the chunk decoder alone would unlock none of the 17 ESP ids.
2. **`PlayerAuthInput` has no `input_data` field** in this build. Movement flags
   moved to `actions_presence` plus an `Action` set whose ordinals the repo does
   not enumerate.

Both are a deliberate stance here: opaque and nested types stay opaque rather
than get guessed. The same stance is why a one-byte header assumption was caught
(`0xbd`'s high bit makes its header two bytes).

## Why the remaining ids are not a backlog of effort

Of the 119 remaining, essentially all are gated on one of three things:

- **A knowledge table this repo does not carry** — enum values, the block
  palette, the item format, the `Action` set. Worth knowing: the block palette
  is **not** in the game APK. It is compiled into the native binary, so
  obtaining it means reverse-engineering that binary, not reading a resource
  file.
- **In-process injection** — 24 ids that change or read the game's own
  rendering and camera.
- **Server authority** — 3 ids, permanently impossible.

## What only a device can do

Nothing here claims `SUPPORTED`, and that word stays at 0 until a live server
round-trip proves it. To get there: install the APK (MIUI blocks CLI install),
grant "display over other apps", start the overlay from the HUD screen, and run
a session. See `docs/DEVICE-*.md` for the runbooks.