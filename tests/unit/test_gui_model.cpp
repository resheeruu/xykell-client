// Host unit test: ClickGUI model built from the REAL registry file.
// This enforces "no second hardcoded catalog": counts must match.
#include <cassert>
#include <iostream>

#include "xykell/clickgui_model.h"
#include "xykell/file_util.h"

int main() {
    using namespace xykell;
    // Repo-relative: tests run with CWD == repo root (see run-unit.sh).
    const auto read = fs::readFile("registry/features.json");
    assert(read.ok);
    const auto rep = gui::buildFromRegistryJson(read.content);
    assert(rep.ok && rep.error.empty());
    assert(rep.entries.size() == 192);

    // Spot checks.
    bool sawAura = false, sawCore = false;
    for (const auto& e : rep.entries) {
        if (e.id == "combat.kill_aura") {
            sawAura = true;
            assert(e.status == "RESEARCH_REQUIRED" && !e.operable());
        }
        if (e.id == "client.core") {
            sawCore = true;
            assert(e.operable());
        }
    }
    assert(sawAura && sawCore);

    // Navigation helpers.
    const auto combat = gui::filterByCategory(rep.entries, "combat");
    assert(!combat.empty() && combat.size() < rep.entries.size());
    const auto found = gui::search(rep.entries, "KILL");
    assert(!found.empty()); // case-insensitive
    const auto operable = gui::onlyOperable(rep.entries);
    assert(!operable.empty() && operable.size() < rep.entries.size());
    for (const auto& e : operable) {
        assert(e.operable());
    }

    // Malformed registry rejected.
    const auto bad = gui::buildFromRegistryJson("{nope");
    assert(!bad.ok && !bad.error.empty());

    std::cout << "test_gui_model: PASS (" << rep.entries.size() << " entries)\n";
    return 0;
}
