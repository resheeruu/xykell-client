#pragma once

// HUD rendering binding: layout + theme + live state -> neutral draw lines.
// No preloader types here (host-testable); xykell_hud.cpp converts to
// DrawCommands. Colors come from the active Theme; malformed hex falls back
// to white. Module list reflects the real ModuleManager state.
#include <cstdint>
#include <string>
#include <vector>

#include "xykell/hud_model.h"
#include "xykell/module_manager.h"
#include "xykell/profile_manager.h"
#include "xykell/theme.h"

namespace xykell::hud {

struct HudLine {
    std::string text;
    float x = 16.0f;
    float y = 48.0f;
    float size = 20.0f;
    std::uint32_t color = 0xFFFFFFFF;
};

// "#RRGGBB" or "#AARRGGBB" -> ARGB uint32; anything else -> opaque white.
std::uint32_t themeColor(const std::string& hex);

struct RenderContext {
    const ui::Theme* theme = nullptr;
    const ModuleManager* modules = nullptr;
    int taps = 0;
    std::string versionLine = "XYKELL";
};

std::vector<HudLine> renderHud(const HudManager& mgr, const RenderContext& ctx);

// Clamp all elements into [0,w]x[0,h] when a viewport is known (w,h > 0).
void clampToViewport(HudLayout& layout, float w, float h);

// Unmistakable load proof banner (ASCII only — game fonts vary). Submitted
// only when XYKELL_RUNTIME_ACTIVE is marked, i.e. code running in-process.
std::vector<HudLine> proofBanner(const std::string& versionLine);

// Profile layout persistence (native store stays authoritative).
void saveHudToProfile(Profile& profile, const HudManager& mgr);
bool loadHudFromProfile(const Profile& profile, HudManager& mgr, std::string& error);

} // namespace xykell::hud
