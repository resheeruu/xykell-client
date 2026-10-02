// Host unit test: portal abstraction with recording fakes. Drives the REAL
// menu/HUD/input code (register/toggle/refresh/routing) without preloader.
#include <cassert>
#include <iostream>

#include "xykell/hud.h"
#include "xykell/core.h"
#include "xykell/menu.h"
#include "xykell/module_manager.h"
#include "xykell/portal.h"

namespace {

struct FakeLogger : xykell::portal::Logger {
    std::vector<std::string> lines;
    void info(const std::string& m) override { lines.push_back("I:" + m); }
    void warn(const std::string& m) override { lines.push_back("W:" + m); }
    void error(const std::string& m) override { lines.push_back("E:" + m); }
};

struct FakeMenu : xykell::portal::MenuRegistry {
    std::vector<std::string> registered;
    std::vector<std::string> unregistered;
    xykell::portal::ToggleCallback toggle;
    bool registerModule(const xykell::portal::MenuModule& m) override {
        registered.push_back(m.moduleId);
        if (m.onToggle) {
            toggle = m.onToggle;
        }
        return true;
    }
    void unregisterModule(const std::string& id) override { unregistered.push_back(id); }
    void setModuleEnabled(const std::string&, bool) override {}
};

struct FakeOverlay : xykell::portal::Overlay {
    std::string lastId;
    std::vector<xykell::portal::OverlayLine> last;
    int submits = 0;
    void submit(const std::string& id,
                const std::vector<xykell::portal::OverlayLine>& lines) override {
        lastId = id;
        last = lines;
        ++submits;
    }
};

struct FakeInput : xykell::portal::Input {
    xykell::portal::TouchCallback cb;
    void onTouch(xykell::portal::TouchCallback c) override { cb = std::move(c); }
};

} // namespace

int main() {
    using namespace xykell;
    FakeLogger log;
    FakeMenu menu;
    FakeOverlay overlay;
    FakeInput input;
    portal::setBackend({&log, &menu, &overlay, &input});

    ModuleManager mods;
    mods.registerModule({"xykell-core", "Xykell Core", "client"});
    setRuntimeModules(&mods);

    // Menu module: toggle callback flips real core state.
    assert(registerMenuModule("test-mod"));
    assert(menu.registered.size() == 1 && menu.registered[0] == "xykell-core");
    XykellCore::instance().setModEnabled(true);
    menu.toggle("xykell-core", false);
    assert(!XykellCore::instance().modEnabled());
    menu.toggle("xykell-core", true);
    assert(XykellCore::instance().modEnabled());

    // HUD module: overlay receives proof lines incl. live tap counter.
    assert(registerHudModule("test-mod"));
    assert(input.cb); // touch callback captured
    assert(!overlay.last.empty());
    bool sawTaps0 = false;
    for (const auto& l : overlay.last) {
        if (l.text == "taps: 0") {
            sawTaps0 = true;
        }
    }
    assert(sawTaps0);
    // Closed-GUI tap: counter increments, overlay refreshes, not consumed.
    assert(input.cb({100.0f, 500.0f, 0}) == false);
    bool sawTaps1 = false;
    for (const auto& l : overlay.last) {
        if (l.text == "taps: 1") {
            sawTaps1 = true;
        }
    }
    assert(sawTaps1);

    // Disable via menu toggle: overlay clears.
    menu.toggle("xykell-core", false);
    input.cb({100.0f, 500.0f, 0});
    assert(overlay.last.empty());

    // Unregister paths hit the backend.
    menu.toggle("xykell-core", true);
    unregisterHudModule();
    unregisterMenuModule();
    assert(menu.unregistered.size() == 2);

    std::cout << "test_portal: PASS\n";
    return 0;
}
