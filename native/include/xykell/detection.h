#pragma once

// Installation detection verdicts. The classic failure: on Android 11+ a
// missing <queries> entry makes PackageManager hide the package, which a
// naive check reports as "not installed". These states never collapse that
// distinction. Pure C++ (host-tested); Android gathers the inputs.
#include <string>

namespace xykell::detect {

enum class InstallState {
    NotInstalled,          // package lookup failed (and visibility granted)
    Installed,             // present, enabled, ABI ok
    Inaccessible,          // present but disabled (or otherwise unusable)
    Unsupported,           // present but ABI/version not supportable
    DetectionError,        // inputs contradictory (e.g. found but no version)
};

std::string stateName(InstallState s);

struct DetectionInput {
    bool packageFound = false;
    std::string version;   // versionName, may be empty
    std::string abi;       // device ABI the query ran on
    bool enabled = true;
    bool queriesGranted = true; // false when caller lacks visibility (API 11+)
};

struct DetectionVerdict {
    InstallState state = InstallState::NotInstalled;
    std::string reason; // human diagnostic, never a credential
};

// arm64-v8a is the only ABI Xykell targets.
DetectionVerdict evaluateDetection(const DetectionInput& in);

} // namespace xykell::detect
