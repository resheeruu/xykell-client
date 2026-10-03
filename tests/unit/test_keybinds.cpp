// Host unit test: keybind system (Batch 4). Deterministic, no platform.
#include <cassert>
#include <iostream>
#include <string>
#include <vector>

#include "xykell/keybinds.h"

using namespace xykell::input;

int main() {
    // --- registration: ids, idempotent, empty rejected ---
    {
        KeybindManager m;
        assert(registerDefaultBinds(m) == 8);
        assert(registerDefaultBinds(m) == 8);  // idempotent descriptions
        assert(m.list().size() == 8);
        assert(!m.registerAction(""));
        assert(!m.isBound("hud.editor.open"));  // registered UNBOUND
        assert(m.get("nope").action.empty());   // unknown: empty bind
    }
    // --- bind/unbind/conflicts ---
    {
        KeybindManager m;
        registerDefaultBinds(m);
        assert(m.bind("hud.editor.open", 101));
        assert(m.bind("hud.editor.open", 102, true));
        assert(m.get("hud.editor.open").primary == 101);
        assert(!m.bind("module.toggle.fps", 101));  // conflict refused
        assert(!m.lastError().empty());             // reason recorded
        assert(!m.isBound("module.toggle.fps"));
        assert(!m.bind("unknown.action", 5));  // unknown action refused
        assert(!m.bind("module.toggle.fps", 0));  // 0 means unbound
        assert(m.bind("module.toggle.fps", 103));
        assert(m.unbind("hud.editor.open"));
        assert(m.bind("module.toggle.cps", 101));  // freed code reusable
        assert(m.press(999).empty());              // unbound: nothing fires
        const auto fired = m.press(103);
        assert(fired.size() == 1 && fired[0] == "module.toggle.fps");
        assert(m.press(102) == std::vector<std::string>{"hud.editor.open"});
        assert(m.boundCodes().size() == 3);
        m.reset();
        assert(m.boundCodes().empty());
        assert(m.list().size() == 8);  // actions kept, binds cleared
    }
    // --- touch-region codes live in the reserved range ---
    {
        KeybindManager m;
        m.registerAction("quick.action");
        assert(m.bind("quick.action", kTouchBase - 7));
        assert(m.press(kTouchBase - 7).size() == 1);
    }

    std::cout << "test_keybinds: PASS\n";
    return 0;
}
