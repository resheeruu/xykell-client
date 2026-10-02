#include "xykell/version_adapter.h"

#include <sstream>

namespace xykell {

const std::vector<std::string>& VersionAdapter::knownGood() {
    static const std::vector<std::string> goods = {"1.26.50"};
    return goods;
}

bool VersionAdapter::archSupported(const std::string& arch) {
    return arch == "arm64-v8a" || arch == "aarch64";
}

int VersionAdapter::compareVersions(const std::string& a, const std::string& b) {
    auto parse = [](const std::string& s, std::vector<long>& out) {
        std::stringstream ss(s);
        std::string part;
        while (std::getline(ss, part, '.')) {
            if (part.empty()) {
                return false;
            }
            try {
                out.push_back(std::stol(part));
            } catch (...) {
                return false;
            }
        }
        return !out.empty();
    };
    std::vector<long> va, vb;
    if (!parse(a, va) || !parse(b, vb)) {
        return -2;
    }
    const std::size_t n = va.size() > vb.size() ? va.size() : vb.size();
    va.resize(n, 0);
    vb.resize(n, 0);
    for (std::size_t i = 0; i < n; ++i) {
        if (va[i] != vb[i]) {
            return va[i] < vb[i] ? -1 : 1;
        }
    }
    return 0;
}

SupportResult VersionAdapter::check(const std::string& minecraftVersion,
                                     const std::string& arch) {
    SupportResult r;
    if (!archSupported(arch)) {
        r.state = SupportState::Unsupported;
        r.reason = "unsupported ABI (need arm64-v8a)";
        r.display = minecraftVersion.empty() ? "unknown" : minecraftVersion;
        return r;
    }
    if (minecraftVersion.empty() || minecraftVersion == "unknown") {
        // Honest state: no verified runtime version source yet (see
        // docs/BEDROCK-COMPATIBILITY.md). Levi's manifest gating is the
        // enforcement point until then — never invent a verdict.
        r.state = SupportState::Partial;
        r.reason = "version string unavailable [RESEARCH REQUIRED]; Levi manifest gating applies";
        r.display = "unknown";
        return r;
    }
    r.display = minecraftVersion;
    const int cmp = compareVersions(minecraftVersion, kMinVersion);
    if (cmp == -2) {
        r.state = SupportState::Partial;
        r.reason = "unparseable version string; treated as unverified";
        return r;
    }
    if (cmp < 0) {
        r.state = SupportState::Unsupported;
        r.reason = std::string("below Levi floor ") + kMinVersion;
        return r;
    }
    for (const auto& g : knownGood()) {
        if (compareVersions(minecraftVersion, g) == 0) {
            r.state = SupportState::Supported;
            r.reason = "matches Levi-verified line";
            return r;
        }
    }
    r.state = SupportState::Partial;
    r.reason = "above floor but not on a Levi-verified line; load with care";
    return r;
}

} // namespace xykell
