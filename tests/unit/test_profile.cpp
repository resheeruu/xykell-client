// Host unit test: profiles CRUD, persistence, import/export, active tracking.
#include <cassert>
#include <cstdlib>
#include <iostream>

#include "xykell/file_util.h"
#include "xykell/profile_manager.h"

namespace {
std::string tmpBase() {
    const char* e = std::getenv("XYKELL_TEST_TMP");
    return e != nullptr ? e : "/data/data/com.termux/files/usr/tmp/opencode";
}
} // namespace

int main() {
    using namespace xykell;
    const std::string root = tmpBase() + "/xprof-1";
    std::string err;
    ProfileManager pm(root);

    // Fresh root: Default auto-created on setActive.
    assert(pm.setActive("Default", err));
    assert(pm.active() == "Default");
    Profile d;
    assert(pm.load("Default", d, err) && d.name == "Default");

    // Save content + reload.
    d.modules["hud.fps"] = true;
    d.theme = "Xykell Light";
    assert(pm.save(d, err));
    Profile d2;
    assert(pm.load("Default", d2, err));
    assert(d2.modules["hud.fps"] && d2.theme == "Xykell Light");

    // Create/duplicate/rename/remove.
    assert(pm.create("PvP", err));
    assert(pm.exists("PvP"));
    assert(!pm.create("PvP", err)); // duplicate rejected
    assert(!pm.create("bad/name", err)); // invalid rejected
    assert(pm.setActive("PvP", err) && pm.active() == "PvP");
    assert(pm.duplicate("PvP", "PvP2", err) && pm.exists("PvP2"));
    assert(pm.rename("PvP2", "PvP3", err) && !pm.exists("PvP2") && pm.exists("PvP3"));
    assert(!pm.rename("Default", "X", err)); // Default protected
    assert(!pm.remove("Default", err)); // Default protected
    assert(pm.remove("PvP3", err) && !pm.exists("PvP3"));

    // Corrupt profile rejected, existing untouched.
    assert(fs::atomicWrite(root + "/profiles/PvP.json", "{broken", err));
    Profile bad;
    assert(!pm.load("PvP", bad, err) && !err.empty());

    // Export/import round-trip.
    const std::string exp = root + "/export.json";
    assert(pm.remove("PvP", err)); // remove corrupt file first
    assert(pm.create("PvP", err));
    assert(pm.exportTo("PvP", exp, err));
    assert(pm.importFrom(exp, "Imported", err) && pm.exists("Imported"));

    // Reset restores builtin.
    assert(pm.reset("PvP", err));
    Profile r;
    assert(pm.load("PvP", r, err) && r.modules.empty());

    std::cout << "test_profile: PASS\n";
    return 0;
}
