// Task 5: HUD/input proof. Verified APIs only (preloader 0.2.3).
#include "xykell/hud.h"

#include <atomic>
#include <vector>

#include <pl/Input.hpp>
#include <pl/ModMenu.hpp>

#include "xykell/core.h"
#include "xykell/version.h"

namespace xykell {

namespace {

// ASSUMPTION (low risk, device-verified in Task 6): color is ARGB.
constexpr std::uint32_t kWhite = 0xFFFFFFFF;
constexpr float kTextSize = 20.0f;

std::atomic<int> gTaps{0};
std::atomic<bool> gHudEnabled{true};

pl::modmenu::DrawCommand textLine(float y, const std::string& s) {
    pl::modmenu::DrawCommand c;
    c.type = pl::modmenu::DrawCommandType::Text;
    c.x = 16.0f;
    c.y = y;
    c.size = kTextSize;
    c.color = kWhite;
    c.text = s;
    return c;
}

} // namespace

void refreshHud() {
    if (!gHudEnabled.load() || !XykellCore::instance().modEnabled()) {
        // Clearing = submitting an empty command list (verified API).
        pl::modmenu::submitDrawCommands(kHudModuleId, {});
        return;
    }
    const auto& info = XykellCore::instance().info();
    std::vector<pl::modmenu::DrawCommand> cmds;
    cmds.push_back(textLine(48.0f, std::string("XYKELL ") + XYKELL_VERSION
                                      + " | mc=" + info.minecraftVersion));
    // Honest placeholders: no verified frame-tick or player-position source yet.
    cmds.push_back(textLine(76.0f, "FPS: -- (frame source [RESEARCH REQUIRED])"));
    cmds.push_back(textLine(104.0f, "coords: n/a (no verified source)"));
    cmds.push_back(textLine(132.0f, "taps: " + std::to_string(gTaps.load())));
    pl::modmenu::submitDrawCommands(kHudModuleId, cmds);
}

bool registerHudModule(const std::string& modId) {
    // NOTE: preloader input callbacks are process-wide with no unregister;
    // enable/disable is enforced inside the callback via the Core flag.
    pl::input::registerTouchCallback([](const pl::input::TouchEvent& ev) {
        (void)ev;
        if (!XykellCore::instance().modEnabled() || !gHudEnabled.load()) {
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
    pl::modmenu::submitDrawCommands(kHudModuleId, {});
    pl::modmenu::unregisterModule(kHudModuleId);
}

} // namespace xykell
