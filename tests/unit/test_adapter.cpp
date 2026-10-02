// Host unit test: VersionAdapter verdicts. No game, no offsets.
#include <cassert>
#include <iostream>

#include "xykell/version_adapter.h"

using xykell::SupportState;
using xykell::VersionAdapter;

int main() {
    // Unknown version: honest PARTIAL, never a fake verdict.
    auto r = VersionAdapter::check("unknown", "arm64-v8a");
    assert(r.state == SupportState::Partial && r.display == "unknown");
    r = VersionAdapter::check("", "arm64-v8a");
    assert(r.state == SupportState::Partial);

    // Verified line.
    r = VersionAdapter::check("1.26.50", "arm64-v8a");
    assert(r.state == SupportState::Supported);

    // Below Levi floor.
    r = VersionAdapter::check("1.20.0", "arm64-v8a");
    assert(r.state == SupportState::Unsupported);

    // Above floor, unverified line.
    r = VersionAdapter::check("1.21.80", "arm64-v8a");
    assert(r.state == SupportState::Partial);

    // Wrong ABI.
    r = VersionAdapter::check("1.26.50", "x86_64");
    assert(r.state == SupportState::Unsupported);

    // Garbage version string.
    r = VersionAdapter::check("abc", "arm64-v8a");
    assert(r.state == SupportState::Partial);

    assert(VersionAdapter::archSupported("arm64-v8a"));
    assert(VersionAdapter::archSupported("aarch64"));
    assert(!VersionAdapter::archSupported("armeabi-v7a"));

    std::cout << "test_adapter: PASS\n";
    return 0;
}
