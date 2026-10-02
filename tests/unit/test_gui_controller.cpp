// Host unit test: GuiController toggle honesty + catalog integrity.
#include <cassert>
#include <iostream>

#include "xykell/file_util.h"
#include "xykell/gui_controller.h"

int main() {
    using namespace xykell;
    const auto read = fs::readFile("registry/features.json");
    assert(read.ok);
    gui::GuiController gui;
    std::string err;
    assert(gui.loadRegistry(read.content, err));
    assert(gui.count() == 225); // registry truth, still exact
    assert(gui.loadRegistry("junk", err)); // cached: no-op success

    ModuleManager mods;
    mods.registerModule({"xykell-core", "Xykell Core", "client"});
    mods.registerModule({"xykell-hud", "Xykell HUD", "hud"});

    // RESEARCH_REQUIRED can never be enabled through ClickGUI.
    assert(gui.requestToggle("xykell.combat.kill_aura", mods)
           == gui::ToggleOutcome::RefusedResearch);
    // NOT_IMPLEMENTED can never be enabled either.
    assert(gui.requestToggle("xykell.scripting.script_runtime", mods)
           == gui::ToggleOutcome::RefusedNotImplemented);
    // Unknown id reported, not performed.
    assert(gui.requestToggle("nope.nope", mods) == gui::ToggleOutcome::UnknownId);
    // Nothing changed.
    assert(mods.get("xykell-core")->state == ModuleState::Loaded);

    // Implemented + operable toggles really flip.
    assert(gui.requestToggle("xykell.client.core", mods) == gui::ToggleOutcome::Performed);
    assert(mods.get("xykell-core")->state == ModuleState::Enabled);
    assert(gui.requestToggle("xykell.client.core", mods) == gui::ToggleOutcome::Performed);
    assert(mods.get("xykell-core")->state == ModuleState::Loaded);

    // Quarantine blocks even implemented entries.
    mods.quarantine("xykell-core", "boom x3");
    assert(gui.requestToggle("xykell.client.core", mods)
           == gui::ToggleOutcome::RefusedQuarantined);

    // Navigation state.
    gui.category = "COMBAT";
    assert(!gui.visible().empty() && gui.visible().size() < gui.count());
    gui.category = "all";
    gui.query = "waypoint";
    assert(!gui.visible().empty());
    gui.query = "";
    assert(gui.visible().size() == gui.count());

    // Favorites are pure UI state.
    gui.toggleFavorite("xykell.hud.fps");
    assert(gui.isFavorite("xykell.hud.fps"));
    gui.toggleFavorite("xykell.hud.fps");
    assert(!gui.isFavorite("xykell.hud.fps"));

    std::cout << "test_gui_controller: PASS\n";
    return 0;
}
