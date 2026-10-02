// Host unit test: engine pure logic + gating (no game behavior).
#include <cassert>
#include <cmath>
#include <iostream>

#include "xykell/engines.h"

int main() {
    using namespace xykell;
    // Target pick: nearest valid, filters respected, deterministic ties.
    engines::TargetConfig cfg;
    const std::vector<engines::TargetCandidate> cs = {
        {"far", 9.0, 5.0, false, false},   // out of range
        {"friend", 2.0, 5.0, true, false},  // filtered
        {"bot", 1.5, 5.0, false, true},     // filtered
        {"b", 3.0, 5.0, false, false},
        {"a", 3.0, 5.0, false, false},      // tie: first-seen (b) wins
    };
    auto pick = engines::pickTarget(cs, cfg);
    assert(pick.found && pick.id == "b");
    assert(engines::pickTarget({}, cfg).found == false);
    const std::vector<engines::TargetCandidate> nan = {{"x", NAN, 0, false, false}};
    assert(!engines::pickTarget(nan, cfg).found);

    // Movement validation.
    assert(engines::validateMovement({1, 0, 0, true, false, "vanilla"}).sane);
    assert(!engines::validateMovement({NAN, 0, 0}).sane);
    assert(!engines::validateMovement({1000, 0, 0}).sane);
    assert(!engines::validateMovement({0, 0, 0, false, false, ""}).sane);

    // Budget enforcement.
    engines::Budget b;
    assert(engines::checkBudget({0, 0, 0, 16, 32, 9}, b).allowed);
    assert(!engines::checkBudget({0, 0, 0, 500, 32, 9}, b).allowed);
    assert(!engines::checkBudget({0, 0, 0, 16, 5000, 9}, b).allowed);

    // Frame budget measurement.
    engines::FrameBudget fb(16.7);
    assert(fb.average() == 0.0 && !fb.overBudget());
    fb.recordFrame(10.0);
    fb.recordFrame(30.0);
    assert(fb.samples() == 2 && fb.worst() == 30.0);
    assert(fb.average() == 20.0 && fb.overBudget());
    fb.recordFrame(-5.0); // garbage ignored
    assert(fb.samples() == 2);

    // Engine gate delegates to capability truth.
    const auto probe = runtime::RuntimeProbe::collect("t", "l", "p");
    assert(engines::engineGate({"LIFECYCLE"}, probe).allowed);
    assert(!engines::engineGate({"PLAYER"}, probe).allowed);

    std::cout << "test_engines: PASS\n";
    return 0;
}
