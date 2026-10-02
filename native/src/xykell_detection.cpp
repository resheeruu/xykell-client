#include "xykell/detection.h"

namespace xykell::detect {

std::string stateName(InstallState s) {
    switch (s) {
        case InstallState::NotInstalled: return "NOT_INSTALLED";
        case InstallState::Installed: return "INSTALLED";
        case InstallState::Inaccessible: return "INSTALLED_BUT_INACCESSIBLE";
        case InstallState::Unsupported: return "INSTALLED_UNSUPPORTED";
        case InstallState::DetectionError: return "DETECTION_ERROR";
    }
    return "?";
}

DetectionVerdict evaluateDetection(const DetectionInput& in) {
    if (!in.packageFound) {
        if (!in.queriesGranted) {
            return {InstallState::DetectionError,
                    "package lookup failed AND visibility not granted: add "
                    "<queries> for the package (Android 11+) before concluding"};
        }
        return {InstallState::NotInstalled, "package lookup failed"};
    }
    if (in.version.empty()) {
        return {InstallState::DetectionError, "package found but version unreadable"};
    }
    if (!in.enabled) {
        return {InstallState::Inaccessible, "package disabled by user/device"};
    }
    if (in.abi != "arm64-v8a" && in.abi != "aarch64") {
        return {InstallState::Unsupported, "ABI " + in.abi + " is not arm64-v8a"};
    }
    return {InstallState::Installed, "package present, enabled, arm64"};
}

} // namespace xykell::detect
