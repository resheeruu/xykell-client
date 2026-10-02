// Host unit test: AI planner grounds goals in availability, never invents.
#include <cassert>
#include <iostream>

#include "xykell/file_util.h"
#include "xykell/planner.h"

int main() {
    using namespace xykell;
    const auto read = fs::readFile("registry/features.json");
    assert(read.ok);
    gui::GuiController gui;
    std::string err;
    assert(gui.loadRegistry(read.content, err));
    ModuleManager mods;
    mods.registerModule({"xykell-core", "Xykell Core", "client"});
    const auto probe = runtime::RuntimeProbe::collect("t", "l", "p");

    // Empty goal: empty, non-executable plan (not a hallucination).
    auto p0 = ai::planForGoal("", gui, probe, mods);
    assert(!p0.executable && p0.steps.empty());

    // "fps hud": matches hud entries with exact per-step truth.
    // NOTE: registry status short-circuits the gate: RR-status entries read
    // RESEARCH_REQUIRED even when their requires are also gate-blocked.
    auto p1 = ai::planForGoal("fps hud", gui, probe, mods);
    assert(!p1.steps.empty() && !p1.executable);
    bool sawFpsRR = false, sawTapsAvail = false, sawPingRR = false;
    for (const auto& s : p1.steps) {
        if (s.moduleId == "xykell.hud.fps") {
            sawFpsRR = (s.availability == "RESEARCH_REQUIRED");
        }
        if (s.moduleId == "xykell.hud.touch_indicators") {
            sawTapsAvail = (s.availability == "AVAILABLE");
        }
        if (s.moduleId == "xykell.hud.ping") {
            sawPingRR = (s.availability == "RESEARCH_REQUIRED");
        }
        assert(!s.reason.empty());
    }
    assert(sawFpsRR && sawTapsAvail && sawPingRR);
    // Gate-level BLOCKED is reachable for non-RR entries whose requires fail
    // on a Blocked capability (synthetic entry — no registry entry like this).
    {
        gui::GuiModuleEntry syn;
        syn.id = "xykell.test.synthetic";
        syn.category = "TEST";
        syn.status = "PARTIAL";
        syn.requiresCaps = {"PACKET"};
        assert(gui.availability(syn, probe, mods) == "BLOCKED");
        syn.requiresCaps = {"LIFECYCLE"};
        assert(gui.availability(syn, probe, mods) == "AVAILABLE");
        syn.requiresCaps = {"PLAYER"};
        assert(gui.availability(syn, probe, mods) == "RESEARCH_REQUIRED");
    }

    // Nonsense goal: no steps.
    auto p2 = ai::planForGoal("xyzzy plugh", gui, probe, mods);
    assert(!p2.executable && p2.steps.empty());

    // Quarantine poisons safety.
    mods.registerModule({"xykell-hud", "Xykell HUD", "hud"});
    mods.quarantine("xykell-hud", "boom");
    auto p3 = ai::planForGoal("touch indicators hud", gui, probe, mods);
    bool sawQuar = false;
    for (const auto& s : p3.steps) {
        if (s.availability == "QUARANTINED") {
            sawQuar = true;
        }
    }
    assert(sawQuar);
    assert(!ai::planIsSafe(p3));

    std::cout << "test_planner: PASS (" << p1.steps.size() << " fps/hud steps)\n";
    return 0;
}
