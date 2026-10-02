// Batch 3+: HUD/input runtime via the Xykell portal. Touch routes through
// InputRouter; overlay lines come from HudRenderer (verified sources only,
// "--" otherwise). Closed GUI preserves exact M1 behavior (tap counter +
// refresh). No pl/ includes here — the backend owns the SDK dependency.
#include "xykell/hud.h"

#include <atomic>
#include <string_view>
#include <vector>

#include "xykell/core.h"
#include "xykell/gui_controller.h"
#include "xykell/hud_renderer.h"
#include "xykell/input_router.h"
#include "xykell/module_manager.h"
#include "xykell/portal.h"
#include "xykell/runtime_active.h"
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
std::string gDataDir;

portal::OverlayLine toLine(const hud::HudLine& l) {
    portal::OverlayLine o;
    o.text = l.text;
    o.x = l.x;
    o.y = l.y;
    o.size = l.size;
    o.color = l.color;
    return o;
}

} // namespace

void refreshHud() {
    auto* overlay = portal::backend().overlay;
    if (overlay == nullptr) {
        return;
    }
    if (!gHudEnabled.load() || !XykellCore::instance().modEnabled()) {
        overlay->submit(kHudModuleId, {});
        return;
    }
    hud::RenderContext ctx;
    ctx.theme = &gTheme;
    ctx.modules = gMods;
    ctx.taps = gTaps.load();
    const auto& info = XykellCore::instance().info();
    ctx.versionLine = std::string("XYKELL ") + XYKELL_VERSION + " | mc=" + info.minecraftVersion;
    std::vector<portal::OverlayLine> lines;
    // Proof banner first: only present when code runs in-process.
    if (!gDataDir.empty() && isRuntimeActive(gDataDir)) {
        for (const auto& line : hud::proofBanner(ctx.versionLine)) {
            portal::OverlayLine o;
            o.text = line.text;
            o.x = line.x;
            o.y = line.y;
            o.size = line.size;
            o.color = line.color;
            lines.push_back(o);
        }
    }
    for (const auto& line : hud::renderHud(gHudMgr, ctx)) {
        lines.push_back(toLine(line));
    }
    overlay->submit(kHudModuleId, lines);
}

bool registerHudModule(const std::string& modId) {
    auto* menu = portal::backend().menu;
    auto* input = portal::backend().input;
    if (menu == nullptr || input == nullptr) {
        return false;
    }
    if (!ui::ThemeManager::find("Xykell Dark", gTheme)) {
        gTheme = ui::Theme{};
    }
    // Registry powers the ClickGUI model; loaded once from the mod resources.
    // Missing file -> GUI stays closed; menu + HUD proof still work.
    // (Resource path itself comes from verified ModContext::resourceDir().)
    gGui.open = false;
    gRouter.setGuiOpen(false);
    input->onTouch([](const portal::TouchPoint& p) {
        if (!XykellCore::instance().modEnabled() || !gHudEnabled.load()) {
            return false;
        }
        if (gRouter.guiOpen()) {
            input::TouchPoint q{p.x, p.y, p.action};
            gRouter.onTouch(q, gGui, *gMods);
            if (!gGui.open) {
                gRouter.setGuiOpen(false);
            }
            return false;
        }
        ++gTaps;
        refreshHud();
        return false; // do not consume; game still receives the touch
    });
    portal::MenuModule m;
    m.moduleId = kHudModuleId;
    m.displayName = "Xykell HUD (M1 proof)";
    m.description = "M1 integration proof: overlay text + tap counter.";
    m.modId = modId;
    m.defaultEnabled = true;
    m.onToggle = [](std::string_view id, bool enabled) {
        (void)id;
        gHudEnabled.store(enabled);
        refreshHud();
    };
    const bool ok = menu->registerModule(m);
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
    if (portal::backend().overlay != nullptr) {
        portal::backend().overlay->submit(kHudModuleId, {});
    }
    if (portal::backend().menu != nullptr) {
        portal::backend().menu->unregisterModule(kHudModuleId);
    }
}

// ClickGUI host API (used by the ModMenu entry + tests).
gui::GuiController& clickGui() { return gGui; }
input::InputRouter& inputRouter() { return gRouter; }
hud::HudManager& hudManager() { return gHudMgr; }
void setRuntimeModules(ModuleManager* mods) { gMods = mods; }
void setHudDataDir(const std::string& dataDir) { gDataDir = dataDir; }

} // namespace xykell
