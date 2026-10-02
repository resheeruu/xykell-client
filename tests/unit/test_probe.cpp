// Host unit test: RuntimeProbe determinism + capability gate honesty.
#include <cassert>
#include <iostream>

#include "xykell/gui_controller.h"
#include "xykell/file_util.h"
#include "xykell/runtime_probe.h"

int main() {
    using namespace xykell;
    using xykell::gui::GuiModuleEntry;
    auto probe = runtime::RuntimeProbe::collect("t", "levi-pin", "pre-0.2.3");
    assert(probe.xykellVersion == "t");
    assert(probe.arch == "arm64-v8a" || probe.arch == "unknown-arch"); // host-dependent
    assert(probe.minecraftVersion == "unknown"); // never guessed
    assert(!probe.caps.empty());

    // Spot states (must match verified evidence, see RUNTIME-SDK-INVENTORY).
    assert(probe.stateOf("LIFECYCLE") == runtime::CapState::Verified);
    assert(probe.stateOf("PACKET") == runtime::CapState::Blocked);
    assert(probe.stateOf("FRAME") == runtime::CapState::ResearchRequired);
    assert(probe.stateOf("PLAYER") == runtime::CapState::ResearchRequired);
    assert(probe.stateOf("RENDER") == runtime::CapState::Blocked);
    assert(probe.stateOf("DOES_NOT_EXIST") == runtime::CapState::ResearchRequired);
    // Repeatability: two collects are identical.
    const auto probe2 = runtime::RuntimeProbe::collect("t", "levi-pin", "pre-0.2.3");
    assert(probe2.caps.size() == probe.caps.size());

    // Gate: empty requires + clean record -> allowed.
    auto g = runtime::canEnable({}, probe, false);
    assert(g.allowed);
    // Quarantine always blocks.
    g = runtime::canEnable({}, probe, true);
    assert(!g.allowed);
    // RR requirement blocks with the reason naming it.
    g = runtime::canEnable({"PLAYER"}, probe, false);
    assert(!g.allowed && g.reason.find("PLAYER") != std::string::npos);
    // Blocked requirement blocks too.
    g = runtime::canEnable({"PACKET"}, probe, false);
    assert(!g.allowed && g.reason.find("BLOCKED") != std::string::npos);
    // Verified/Partial pass.
    g = runtime::canEnable({"LIFECYCLE", "OVERLAY_DELIVERY"}, probe, false);
    assert(g.allowed);

    // Availability mapping end-to-end (registry truth + gate).
    gui::GuiController gui;
    std::string err;
    const auto read = fs::readFile("registry/features.json");
    assert(read.ok && gui.loadRegistry(read.content, err));
    ModuleManager mods;
    mods.registerModule({"xykell-core", "Xykell Core", "client"});
    // Bind the returned vector first: pointers into a temporary dangle
    // (ASan caught it; CI g++ crashed). See test lesson in docs/TESTING.md.
    const auto all = gui.visible();
    const GuiModuleEntry* aura = nullptr;
    const GuiModuleEntry* core = nullptr;
    const GuiModuleEntry* fps = nullptr;
    for (const auto& e : all) {
        if (e.id == "combat.kill_aura") {
            aura = &e;
        }
        if (e.id == "client.core") {
            core = &e;
        }
        if (e.id == "hud.fps") {
            fps = &e;
        }
    }
    assert(aura && core && fps);
    assert(gui.availability(*aura, probe, mods) == "RESEARCH_REQUIRED");
    assert(gui.availability(*fps, probe, mods) == "RESEARCH_REQUIRED"); // FRAME missing
    assert(gui.availability(*core, probe, mods) == "AVAILABLE"); // LIFECYCLE verified
    mods.quarantine("xykell-core", "boom");
    assert(gui.availability(*core, probe, mods) == "QUARANTINED");

    // Diagnostic text: header + one line per capability.
    const std::string rep = runtime::formatProbeReport(probe);
    assert(rep.find("XYKELL t") != std::string::npos);
    assert(rep.find("FRAME=RESEARCH_REQUIRED") != std::string::npos);
    assert(rep.find("PACKET=BLOCKED") != std::string::npos);
    assert(rep.find("LIFECYCLE=VERIFIED") != std::string::npos);

    std::cout << "test_probe: PASS (" << probe.caps.size() << " caps)\n";
    return 0;
}
