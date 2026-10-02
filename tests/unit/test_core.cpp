// Host unit test: XykellCore lifecycle. Pure C++, no device needed.
#include <cassert>
#include <iostream>

#include "xykell/core.h"

int main() {
    auto& core = xykell::XykellCore::instance();
    assert(!core.initialized());
    assert(core.init("0.0-test", "pin-test"));
    assert(core.initialized());
    assert(core.init("other", "other")); // idempotent
    assert(core.info().xykellVersion == "0.0-test"); // first init wins
    const auto caps = core.info().capabilities;
    assert(caps & static_cast<std::uint32_t>(xykell::Capability::Lifecycle));
    assert(caps & static_cast<std::uint32_t>(xykell::Capability::Logging));
    core.setSafeMode(true);
    core.setModEnabled(false);
    core.setDebugLogging(true);
    assert(core.info().safeMode && !core.modEnabled() && core.info().debugLogging);
    core.addCapability(xykell::Capability::Hud);
    assert(core.info().capabilities & static_cast<std::uint32_t>(xykell::Capability::Hud));
    core.shutdown();
    assert(!core.initialized());
    assert(core.info().capabilities == 0);
    std::cout << "test_core: PASS\n";
    return 0;
}
