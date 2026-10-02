# UI system (touch-first)

Not a shrunk desktop ClickGUI. Phone is the primary platform.

## 1. Surfaces
- **HUD engine**: FPS/CPS/coords/direction/ping/server/memory/clock/session/armor/durability/potions/health/hunger/item-count/speed/velocity/combo/target/kills/death-pos/biome/TPS-tablist-where-obtainable/keystrokes/notifications/custom text+widgets. Every widget: drag, resize, scale, alignment, opacity, colors, visibility conditions, presets, reset, per-profile layout, touch editor.
- **ClickGUI**: XYKELL menu — search, categories (Combat/Movement/Visual/HUD/Player/World/Network/Performance/Scripts/Misc), favorites, recent, descriptions, toggles/sliders/dropdowns/color picker, keybind + touchbind editors, profiles, compat + dependency status, experimental warnings.
- **Quick controls**: floating buttons, quick-toggle panel, gestures (swipe, long-press, double-tap, configurable), one-handed mode, landscape optimization, UI scaling, notch/cutout + safe-area handling.
- **Inputs**: touch first; keyboard/mouse/controller where available.

## 2. M1 visible target (proof only)
```
XYKELL
[ON] Xykell Core
[ON] FPS
[ON] Coordinates
Minecraft: <detected version>
Xykell: <version>
```

## 3. Rules
Original Xykell design (no copied artwork/branding). Large touch targets (min 48dp). Every destructive action (reset profile, clear layout) confirms first. UI never blocks the game thread — overlay renders off the critical path.
