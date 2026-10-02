#pragma once

#include <string>

// Task 5: HUD + input proof. Verified APIs only:
//   pl::modmenu::submitDrawCommands (overlay text)
//   pl::input::registerTouchCallback (tap counter)
// Honest limits (see docs/M1-RESULTS.md): no verified per-frame tick source
// (FPS shows "--"), no verified player-position source (coords show "n/a").
// What IS proven: overlay renders, input arrives, enable/disable works.
namespace xykell {

inline constexpr const char* kHudModuleId = "xykell-hud";

bool registerHudModule(const std::string& modId);
void unregisterHudModule();
void refreshHud(); // (re)submits current overlay text; no-op when disabled

} // namespace xykell
