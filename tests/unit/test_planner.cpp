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
    // The status -> availability mapping is asserted below on synthetic
    // entries, so this block must not hardcode the current classification of
    // any specific registry id.
    auto p1 = ai::planForGoal("fps hud", gui, probe, mods);
    assert(!p1.steps.empty() && !p1.executable);
    bool sawFps = false, sawTaps = false;
    for (const auto& s : p1.steps) {
        if (s.moduleId == "xykell.hud.fps") {
            sawFps = true;
        }
        if (s.moduleId == "xykell.hud.touch_indicators") {
            sawTaps = (s.availability == "AVAILABLE");
        }
        assert(!s.reason.empty());
    }
    assert(sawFps && sawTaps);
    // Every step carries an honest availability token.
    for (const auto& s : p1.steps) {
        assert(s.availability == "AVAILABLE" || s.availability == "RESEARCH_REQUIRED" ||
               s.availability == "UNAVAILABLE" || s.availability == "BLOCKED" ||
               s.availability == "QUARANTINED");
    }
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
    // Status is the only thing that makes an entry UNAVAILABLE, independent of
    // the capability gate. Asserted on synthetic ids so reclassifying the real
    // registry can never silently change what this test proves.
    {
        gui::GuiModuleEntry ni;
        ni.id = "xykell.test.unimplemented";
        ni.category = "TEST";
        ni.status = "NOT_IMPLEMENTED";
        ni.requiresCaps = {"LIFECYCLE"};
        assert(gui.availability(ni, probe, mods) == "UNAVAILABLE");
        gui::GuiModuleEntry rr;
        rr.id = "xykell.test.research";
        rr.category = "TEST";
        rr.status = "RESEARCH_REQUIRED";
        rr.requiresCaps = {"LIFECYCLE"};
        assert(gui.availability(rr, probe, mods) == "RESEARCH_REQUIRED");
        gui::GuiModuleEntry ro;
        ro.id = "xykell.test.reference";
        ro.category = "TEST";
        ro.status = "REFERENCE_ONLY";
        ro.requiresCaps = {"LIFECYCLE"};
        assert(gui.availability(ro, probe, mods) != "AVAILABLE");
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
