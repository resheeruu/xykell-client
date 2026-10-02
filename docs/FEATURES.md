# Xykell features (product view)

Xykell Client = launcher + native runtime + ClickGUI + HUD + profiles +
themes + diagnostics, all touch-first and Android-first. What works today
vs what is planned is tracked per-feature in `registry/features.json`
(statuses) and human-readable in `docs/MODULE-CATALOG.md`.

- Works (build-verified, device pending): core lifecycle, config/profiles/
  crashguard stores, menu toggles, HUD proof overlay, tap routing, gating,
  engines (pure), diagnostics formatting, launcher shell + registry browser
  + native-backed profiles + PLAY handoff.
- Planned with honest blockers: everything requiring FRAME/PLAYER/ENTITY/
  WORLD/CAMERA/RENDER/PACKET runtime sources (see RUNTIME-CAPABILITIES.md).
- Never: fake toggles, copied branding/assets/code, credential handling.
