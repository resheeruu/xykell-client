// Host unit test: detection verdicts never collapse visibility failure
// into "not installed".
#include <cassert>
#include <iostream>

#include "xykell/detection.h"

int main() {
    using namespace xykell::detect;
    assert(stateName(InstallState::Installed) == "INSTALLED");
    // The historical bug: no <queries> -> hidden package -> must NOT read
    // as NOT_INSTALLED.
    auto v = evaluateDetection({false, "", "arm64-v8a", true, false});
    assert(v.state == InstallState::DetectionError);
    // Genuine absence (visibility granted).
    v = evaluateDetection({false, "", "arm64-v8a", true, true});
    assert(v.state == InstallState::NotInstalled);
    // Happy path.
    v = evaluateDetection({true, "1.26.45.1", "arm64-v8a", true, true});
    assert(v.state == InstallState::Installed);
    // Disabled package.
    v = evaluateDetection({true, "1.26.45.1", "arm64-v8a", false, true});
    assert(v.state == InstallState::Inaccessible);
    // Wrong ABI.
    v = evaluateDetection({true, "1.26.45.1", "x86_64", true, true});
    assert(v.state == InstallState::Unsupported);
    // Found but version unreadable.
    v = evaluateDetection({true, "", "arm64-v8a", true, true});
    assert(v.state == InstallState::DetectionError);
    assert(!v.reason.empty());
    std::cout << "test_detection: PASS\n";
    return 0;
}
