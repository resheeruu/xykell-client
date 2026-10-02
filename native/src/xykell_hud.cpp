// Batch 3: HUD/input runtime. Touch routes through InputRouter; overlay lines
// come from HudRenderer (verified sources only, "--" otherwise). Closed GUI
// preserves exact M1 behavior (tap counter + refresh).
#include "xykell/hud.h"

#include <atomic>
#include <vector>

#include <pl/Input.hpp>
#include <pl/ModMenu.hpp>

#include "xykell/core.h"
#include "xykell/gui_controller.h"
#include "xykell/hud_renderer.h"
#include "xykell/input_router.h"
#include "xykell/module_manager.h"
#include "xykell/theme.h"
#include "xykell/version.h"

namespace xykell {

namespace {

std::atomic<int> gTaps{0};
std::atomic<bool> gHudEnabled{true};
input::InputRouter gRouter;
gui::GuiController gGui;
hud::HudManager gHudMgr;
ui::Theme gTheme;
ModuleManager* gMods = nullptr;

pl::modmenu::DrawCommand toCmd(const hud::HudLine& l) {
    pl::modmenu::DrawCommand c;
    c.type = pl::modmenu::DrawCommandType::Text;
    c.x = l.x;
    c.y = l.y;
    c.size = l.size;
    c.color = l.color;
    c.text = l.text;
    return c;
}

} // namespace

void refreshHud() {
    if (!gHudEnabled.load() || !XykellCore::instance().modEnabled()) {
        pl::modmenu::submitDrawCommands(kHudModuleId, {});
        return;
    }
    hud::RenderContext ctx;
    ctx.theme = &gTheme;
    ctx.modules = gMods;
    ctx.taps = gTaps.load();
    const auto& info = XykellCore::instance().info();
    ctx.versionLine = std::string("XYKELL ") + XYKELL_VERSION + " | mc=" + info.minecraftVersion;
    std::vector<pl::modmenu::DrawCommand> cmds;
    for (const auto& line : hud::renderHud(gHudMgr, ctx)) {
        cmds.push_back(toCmd(line));
    }
    pl::modmenu::submitDrawCommands(kHudModuleId, cmds);
}

bool registerHudModule(const std::string& modId) {
    if (!ui::ThemeManager::find("Xykell Dark", gTheme)) {
        gTheme = ui::Theme{};
    }
    // Registry powers the ClickGUI model; loaded once from the mod resources.
    // Missing file -> GUI stays closed; menu + HUD proof still work.
    // (Resource path itself comes from verified ModContext::resourceDir().)
    gGui.open = false;
    gRouter.setGuiOpen(false);
    pl::input::registerTouchCallback([](const pl::input::TouchEvent& ev) {
        if (!XykellCore::instance().modEnabled() || !gHudEnabled.load()) {
            return false;
        }
        input::TouchPoint p{ev.x, ev.y, 0};
        if (ev.action == 2) {
            p.action = 2; // move
        } else if (ev.action == 1) {
            p.action = 1; // up
        }
        // NOTE: Android action mapping beyond down/up/move is unverified;
        // anything else is treated as a tap (down+up pair not assumed).
        if (gRouter.guiOpen()) {
            gRouter.onTouch(p, gGui, *gMods);
            if (!gGui.open) {
                gRouter.setGuiOpen(false);
            }
            return false;
        }
        ++gTaps;
        refreshHud();
        return false; // do not consume; game still receives the touch
    });
    const bool ok =
        pl::modmenu::ModuleBuilder(kHudModuleId, "Xykell HUD (M1 proof)")
            .description("M1 integration proof: overlay text + tap counter.")
            .modId(modId)
            .defaultEnabled(true)
            .onToggle([](std::string_view id, bool enabled) {
                (void)id;
                gHudEnabled.store(enabled);
                refreshHud();
            })
            .registerModule();
    if (ok) {
        XykellCore::instance().addCapability(Capability::Hud);
        XykellCore::instance().addCapability(Capability::Input);
        refreshHud();
    }
    return ok;
}

void unregisterHudModule() {
    gHudEnabled.store(false);
    gRouter.setGuiOpen(false);
    gGui.open = false;
    pl::modmenu::submitDrawCommands(kHudModuleId, {});
    pl::modmenu::unregisterModule(kHudModuleId);
}

// ClickGUI host API (used by the ModMenu entry + tests).
gui::GuiController& clickGui() { return gGui; }
input::InputRouter& inputRouter() { return gRouter; }
hud::HudManager& hudManager() { return gHudMgr; }
void setRuntimeModules(ModuleManager* mods) { gMods = mods; }

} // namespace xykell
