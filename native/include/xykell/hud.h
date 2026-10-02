#pragma once

#include <string>

#include "xykell/gui_controller.h"
#include "xykell/hud_model.h"
#include "xykell/input_router.h"

// Batch 3: HUD/input runtime. Closed GUI preserves M1 behavior exactly
// (tap counter + overlay refresh, nothing consumed). Verified APIs only:
//   pl::modmenu::submitDrawCommands (overlay text)
//   pl::input::registerTouchCallback (tap/scroll/drag stream)
// Honest limits: no verified per-frame tick source (FPS "--"), no verified
// player-position source (coords "n/a").
namespace xykell {

class ModuleManager;

inline constexpr const char* kHudModuleId = "xykell-hud";
inline constexpr const char* kClickGuiModuleId = "xykell-clickgui";
inline constexpr const char* kRecoveryModuleId = "xykell-recovery";

bool registerHudModule(const std::string& modId);
void unregisterHudModule();
void refreshHud(); // (re)submits current overlay text; no-op when disabled

bool registerClickGuiModule(const std::string& modId, const std::string& registryJson);
void unregisterClickGuiModule();

bool registerRecoveryModule(const std::string& modId, const std::string& report);
void unregisterRecoveryModule();

// Runtime/test accessors (no game involved).
gui::GuiController& clickGui();
input::InputRouter& inputRouter();
hud::HudManager& hudManager();
void setRuntimeModules(ModuleManager* mods);

class CrashGuard;
void setRecoveryContext(CrashGuard* guard, ModuleManager* mods);

} // namespace xykell
