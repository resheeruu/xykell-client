#include "xykell/sigscan.h"

namespace xykell::sigs {

bool SignaturePattern::valid(std::string& error) const {
    if (bytes.empty() || bytes.size() != mask.size()) {
        error = "pattern/mask empty or length mismatch";
        return false;
    }
    bool anyFixed = false;
    for (const auto m : mask) {
        if (m != 0) {
            anyFixed = true;
        }
        if (m != 0 && m != 0xFF) {
            error = "mask bytes must be 0x00 or 0xFF";
            return false;
        }
    }
    if (!anyFixed) {
        error = "all-wildcard pattern matches everything";
        return false;
    }
    return true;
}

ScanResult scan(const std::vector<Byte>& buffer, const SignaturePattern& pat) {
    ScanResult r;
    if (pat.bytes.empty() || pat.bytes.size() != pat.mask.size()
        || buffer.size() < pat.bytes.size()) {
        return r;
    }
    const std::size_t n = pat.bytes.size();
    for (std::size_t i = 0; i + n <= buffer.size(); ++i) {
        bool hit = true;
        for (std::size_t j = 0; j < n; ++j) {
            if (pat.mask[j] != 0 && buffer[i + j] != pat.bytes[j]) {
                hit = false;
                break;
            }
        }
        if (hit) {
            r.offsets.push_back(i);
        }
    }
    return r;
}

Validation validate(const ScanResult& r, std::size_t bufferSize,
                    std::size_t expectedMaxOffset) {
    if (r.offsets.empty()) {
        return {Verdict::NotFound, 0, "no candidate"};
    }
    if (r.offsets.size() > 1) {
        return {Verdict::Ambiguous, 0,
                "candidates: " + std::to_string(r.offsets.size())};
    }
    if (r.offsets[0] > expectedMaxOffset || r.offsets[0] >= bufferSize) {
        return {Verdict::NotFound, 0, "candidate outside expected range"};
    }
    return {Verdict::Supported, r.offsets[0], "unique candidate in range"};
}

bool SignatureDatabase::add(VersionProfile profile, std::string& error) {
    if (profile.version.empty()) {
        error = "empty version";
        return false;
    }
    if (profiles_.count(profile.version) != 0) {
        error = "duplicate version profile";
        return false;
    }
    for (const auto& e : profile.entries) {
        std::string perr;
        if (!e.pattern.valid(perr)) {
            error = e.id + ": " + perr;
            return false;
        }
        if (e.source.empty() || e.evidence.empty()) {
            error = e.id + ": provenance/evidence required";
            return false;
        }
    }
    profiles_.emplace(profile.version, std::move(profile));
    return true;
}

const VersionProfile* SignatureDatabase::profile(const std::string& version) const {
    const auto it = profiles_.find(version);
    return it == profiles_.end() ? nullptr : &it->second;
}

std::vector<std::string> SignatureDatabase::versions() const {
    std::vector<std::string> out;
    for (const auto& [v, p] : profiles_) {
        (void)p;
        out.push_back(v);
    }
    return out;
}

} // namespace xykell::sigs
