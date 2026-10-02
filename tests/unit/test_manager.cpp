// Host unit test: ModuleManager registry + quarantine.
#include <cassert>
#include <iostream>

#include "xykell/module_manager.h"

int main() {
    xykell::ModuleManager m;
    assert(m.registerModule({"a", "A", "client"}));
    assert(!m.registerModule({"a", "dup", "client"})); // duplicate id
    assert(!m.registerModule({"", "empty", "client"})); // empty id
    assert(m.setEnabled("a", true));
    assert(m.get("a")->state == xykell::ModuleState::Enabled);
    assert(!m.setEnabled("missing", true)); // unknown id
    m.quarantine("a", "test crash x2");
    assert(m.get("a")->state == xykell::ModuleState::Quarantined);
    assert(m.get("a")->quarantineReason == "test crash x2");
    assert(!m.setEnabled("a", true)); // quarantined stays off
    assert(m.unregister("a"));
    assert(m.get("a") == nullptr);
    assert(m.list().empty());
    std::cout << "test_manager: PASS\n";
    return 0;
}
