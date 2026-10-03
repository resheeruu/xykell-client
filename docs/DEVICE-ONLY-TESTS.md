# Xykell device-only tests (batched; automation covers everything else)

Manual testing is reserved for behavior automation cannot establish:
real Minecraft interaction, touch/rendering, Android lifecycle, visual
quality, final release smoke. Everything else MUST be automated and CI.

## Smoke batch (per release candidate)

- [ ] Clean install → first launch → onboarding → Home renders correctly
- [ ] Minecraft detected (installed) with correct version displayed
- [ ] PLAY pipeline → Minecraft launches via system intent
- [ ] HUD renders on screen (default layout, no overlap on test device)
- [ ] Touch: buttons, ClickGUI nav, HUD editor drag/pinch
- [ ] Profiles: switch profile → HUD/modules follow → persist across restart
- [ ] Settings screens render; validation + reset work
- [ ] Shutdown/restart leaves no stale state

## Lifecycle soak batch (per release candidate)

- [ ] Minecraft foreground 10+ min, Xykell background: process alive
- [ ] Observation session (when live source exists) survives app switch
- [ ] Screen off/on: service/session state coherent, no stale snapshot
- [ ] Android back/Home/recents navigation never strands UI state
- [ ] Memory stable across soak (no growth trend)

## Visual/UI batch (per major UI change)

- [ ] Startup animation smooth on test device; reduced-motion honored
- [ ] Theme switching coherent across all screens
- [ ] Batch 10 theme picker: all 7 builtin rows show swatches + ACTIVE
      marker on the active theme; selecting one re-themes immediately and
      survives app restart; RESET TO DEFAULT returns to Xykell Dark;
      invalid stored value falls back to default palette without crash
- [ ] Batch 12 module catalog: 255 entries grouped by category, search
      filters live, row tap expands detail (id/caps/settings/evidence),
      preference switches appear ONLY on SUPPORTED/PARTIAL rows, toggling
      persists into the active profile and survives restart, RESEARCH_
      REQUIRED rows never show a switch
- [ ] Touch targets ≥48dp; contrast readable; typography consistent
- [ ] Empty/loading/error states render (airplane mode, no Minecraft,
      corrupt config, storage denied)
- [ ] Localization spot-check (EN + one more locale)

## Release batch (final gate only)

- [ ] Clean install, permissions rationale shown, first launch clean
- [ ] Minecraft launch → core features → settings → profiles → HUD
- [ ] Shutdown → restart → state intact, no crash logs
- [ ] APK size recorded vs previous release with attribution
- [ ] Security pass: no secrets in logs, no unexpected permissions,
      no network beyond declared surfaces
- [ ] Reference parity spot-check (no cloned branding/assets)

## Out of scope for manual testing

Unit/integration/config/translation/ordering/crypto/registry/CI —
automated (host 27+ suites, lab 58, JVM 15, CI workflows). Do NOT
manually re-verify what CI already proves. The single guided Stage-20
live session is tracked separately (user-gated, one controlled run).
