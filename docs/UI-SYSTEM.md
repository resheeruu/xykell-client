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

## 4. Foundations (Batch 2, data layer; render binding later)
- ClickGUI model (`clickgui_model`): entries parsed ONLY from `registry/features.json` (test-enforced, 192 entries); category filter, case-insensitive search, `operable()` == SUPPORTED|PARTIAL — RESEARCH_REQUIRED modules are display-only, never togglable into fake operation.
- HUD framework (`hud_model`): typed elements, layouts, touch editor state (select/drag/scale/visibility/reset), JSON serialization into profiles. Unverified sources render `--` by contract (`provider` empty or throwing → placeholder).
- Themes (`theme.h`): Xykell Dark (matches `app/` `#0D1526`/`#4FD8C7`) + Xykell Light; `#`-color validated serialization.

## 5. Runtime binding (Batch 3)
- `GuiController`: registry cached once; `requestToggle()` returns Performed
  only for operable entries WITH implementations (`client.core`,
  `client.config_store`, `client.version_adapter`, `hud.watermark`,
  `hud.touch_indicators`); RR/NI/quarantined/unknown refused with reasons.
  GUI opens via the `xykell-clickgui` ModMenu toggle; header-tap closes.
- `InputRouter`: closed GUI → ToGame (M1 counter path unchanged); open GUI →
  taps/scroll consumed (96px rows, clamped scroll, drag≠tap). No new input API.
- `HudRenderer`: layout + theme + live state (taps, module on/total, version)
  → draw lines; theme accent/muted applied; `#`-hex fallback white.
  Editor clamps to viewport when known; cancel keeps prior layout.
- Recovery: safe-mode registers `xykell-recovery` (report as description +
  explicit `clear_quarantine` toggle; cleared modules return to Loaded, never
  auto-enabled).
- Launcher `Modules` screen reads the same `registry/features.json` (build-time
  asset copy, org.json parsing, search + status counts). Profiles/Settings show
  the native-store bridge as NOT WIRED — no duplicate Kotlin store.
