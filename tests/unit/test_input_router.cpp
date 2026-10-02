// Host unit test: InputRouter routing, hit-testing, scroll, open/close.
#include <cassert>
#include <iostream>

#include "xykell/file_util.h"
#include "xykell/input_router.h"

int main() {
    using namespace xykell;
    const auto read = fs::readFile("registry/features.json");
    assert(read.ok);
    gui::GuiController gui;
    std::string err;
    assert(gui.loadRegistry(read.content, err));
    ModuleManager mods;
    mods.registerModule({"xykell-core", "Xykell Core", "client"});
    mods.registerModule({"xykell-hud", "Xykell HUD", "hud"});
    input::InputRouter router;
    router.setViewport(1080.0f, 1920.0f);

    // Closed GUI: everything goes to the game (M1 regression).
    input::TouchPoint tap{100.0f, 500.0f, 0};
    assert(router.onTouch(tap, gui, mods) == input::Route::ToGame);

    // Open GUI: consumed, tap on first row toggles client.core (implemented).
    router.setGuiOpen(true);
    gui.open = true;
    const float y0 = router.headerHeight + 10.0f; // first row
    assert(router.onTouch({100.0f, y0, 0}, gui, mods) == input::Route::ToGui);
    // First visible entry is combat.aim_assist (RR): refused, nothing flips.
    assert(router.onTouch({100.0f, y0, 1}, gui, mods) == input::Route::ToGui);
    assert(mods.get("xykell-core")->state == ModuleState::Loaded);

    // Tap on client.core row toggles it. Find its row index.
    const auto vis = gui.visible();
    int row = -1;
    for (int i = 0; i < static_cast<int>(vis.size()); ++i) {
        if (vis[static_cast<std::size_t>(i)].id == "client.core") {
            row = i;
        }
    }
    assert(row >= 0);
    const float yc = router.headerHeight + (row + 0.5f) * router.rowHeight;
    router.onTouch({100.0f, yc, 0}, gui, mods);
    router.onTouch({100.0f, yc, 1}, gui, mods);
    assert(mods.get("xykell-core")->state == ModuleState::Enabled);

    // Vertical drag scrolls; scroll clamps at top.
    router.onTouch({100.0f, 1000.0f, 0}, gui, mods);
    router.onTouch({100.0f, 500.0f, 2}, gui, mods); // drag up
    assert(router.scroll() > 0.0f);
    router.onTouch({100.0f, 500.0f, 2}, gui, mods);
    router.onTouch({100.0f, 500.0f, 1}, gui, mods); // was a drag, not a tap
    assert(mods.get("xykell-core")->state == ModuleState::Enabled); // unchanged

    // Header tap closes.
    assert(router.onTouch({100.0f, 10.0f, 0}, gui, mods) == input::Route::ToGui);
    assert(!gui.open);
    assert(router.onTouch({100.0f, 500.0f, 0}, gui, mods) == input::Route::ToGame);

    std::cout << "test_input_router: PASS\n";
    return 0;
}
