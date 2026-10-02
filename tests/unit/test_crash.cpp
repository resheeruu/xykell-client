// Host unit test: CrashGuard counts, auto-quarantine, persistence, safe mode.
#include <cassert>
#include <cstdlib>
#include <iostream>

#include "xykell/crash_guard.h"
#include "xykell/file_util.h"

namespace {
std::string tmpBase() {
    const char* e = std::getenv("XYKELL_TEST_TMP");
    return e != nullptr ? e : "/data/data/com.termux/files/usr/tmp/opencode";
}
} // namespace

int main() {
    using namespace xykell;
    const std::string root = tmpBase() + "/xcrash-1";
    std::string err;
    CrashGuard g(root);

    assert(!g.isSafeMode());
    assert(g.crashCount("m") == 0);
    assert(!g.recordCrash("m", "segv")); // 1: no quarantine yet
    assert(!g.recordCrash("m", "segv")); // 2
    assert(g.recordCrash("m", "segv")); // 3: threshold -> quarantined
    assert(g.isQuarantined("m"));
    assert(!g.quarantineReason("m").empty());

    // Persistence across restart.
    assert(g.save(err));
    CrashGuard g2(root);
    assert(g2.load(err));
    assert(g2.isQuarantined("m") && g2.crashCount("m") == 3);

    // Clearing really clears.
    g2.clearQuarantine("m");
    assert(!g2.isQuarantined("m") && g2.crashCount("m") == 0);
    assert(g2.save(err));
    CrashGuard g3(root);
    assert(g3.load(err) && !g3.isQuarantined("m"));

    // Safe mode + report.
    g3.enterSafeMode("test reason");
    g3.quarantineModule("n", "bad hook");
    assert(g3.save(err));
    CrashGuard g4(root);
    assert(g4.load(err) && g4.isSafeMode());
    std::size_t disabled = 0;
    const std::string rep = g4.safeModeReport(disabled);
    assert(disabled == 1);
    assert(rep.find("SAFE MODE") != std::string::npos);
    assert(rep.find("test reason") != std::string::npos);
    assert(rep.find("Disabled modules: 1") != std::string::npos);
    g4.exitSafeMode();
    assert(!g4.isSafeMode());

    // Corrupt state file: load fails loudly (caller enters safe mode).
    assert(fs::atomicWrite(root + "/crashguard.json", "[[[", err));
    CrashGuard g5(root);
    assert(!g5.load(err) && !err.empty());

    std::cout << "test_crash: PASS\n";
    return 0;
}
