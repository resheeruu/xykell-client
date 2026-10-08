# HUD engine (spec; M1 proof only so far)

## Proven (M1)
Static overlay text via verified `submitDrawCommands` + tap counter via
verified `registerTouchCallback`. Placeholders shown honestly (`FPS: --`,
`coords: n/a`) until §Sources land.

## Overlay window (launcher process, no game hook)

The HUD is now painted **over the running game** by
`ui/HudOverlayService`, an ordinary `TYPE_APPLICATION_OVERLAY` window:

- `NativeHud.renderHudLines(root, profile)` renders in the launcher process
  through the *same* C++ renderer the game-side overlay uses
  (`xykell_hud_renderer.cpp`) and returns the draw lines as JSON. One renderer,
  so the text the launcher draws is the text `test_motion_hud` already pins.
- `HudOverlayLines` parses those lines (pure, host-tested); a malformed entry
  is dropped rather than drawn at a guessed position.
- `HudOverlayService` paints them at 4 Hz on a canvas, clamping each coordinate
  into the viewport exactly as the renderer does.
- Providers read the process-wide `ObservationConsumer`, the same one the JNI
  offers feed. **A value the relay never observed renders as `--`**, pinned by
  the overlay-path case in `test_hud_render`. Nothing defaults to zero.
- Started and stopped by hand from the HUD screen. The special
  `SYSTEM_ALERT_WINDOW` grant is requested by sending the user to system
  settings and then re-reading the real answer on resume — never assumed, and
  never started without `Settings.canDrawOverlays`.

**What this is not:** it cannot change what the game itself draws. Renderer and
camera features (xray, `gui_scale`, shaders, `free_cam`, zoom, motion blur,
crosshair replacement, view model) need a hook inside the game process and stay
`REFERENCE_ONLY`. The overlay only ever adds our own draw calls on top.

`app/src/main/cpp/CMakeLists.txt` and `scripts/build-apk.sh` both link the
renderer for this reason; `xykell_hud.cpp` is deliberately *not* linked into the
launcher because it talks to the game-side portal backend.

## Engine (M2+)
Widget registry (one entry per `hud.*` in `registry/features.json`): movable,
scalable, toggleable, configurable, profile-aware; touch-first editor
(drag/resize); persistence in profile JSON.

## Sources (all RESEARCH_REQUIRED until verified)
Frame ticks (live FPS/CPS-timing), player position/rotation, inventory,
latency, world/biome data, chat, target info. No widget displays a value
without its source; unwired widgets stay hidden or show their status.
