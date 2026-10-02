#pragma once

// Runtime capability probe: read-only snapshot of what Xykell can actually
// use in THIS process. States are assigned from verified sources only:
// something compiles != something works. The probe never touches game
// memory, installs hooks, or reads credentials — it reports the integration
// surface Xykell itself owns (lifecycle, config dirs, callbacks registered).
#include <string>
#include <vector>

namespace xykell::runtime {

enum class CapState {
    Verified,          // demonstrated (build + logic +/ device where noted)
    Partial,           // transport exists, semantics unverified
    ResearchRequired,  // no verified source yet
    Blocked,           // no API surface exists in the pinned SDK
    NotImplemented,    // designed, not built
};

std::string stateName(CapState s);

struct Capability {
    std::string id;      // e.g. "FRAME", "PLAYER", "OVERLAY"
    CapState state = CapState::ResearchRequired;
    std::string evidence; // exact source: file/symbol/doc/version
};

struct ProbeReport {
    std::string xykellVersion;
    std::string leviPin;       // LeviLaunchroid commit pinned
    std::string preloaderPin;  // preloader-android tag pinned
    std::string arch;          // compile-time ABI
    std::string minecraftVersion; // "unknown" until a verified source exists
    std::vector<Capability> caps;

    const Capability* find(const std::string& id) const;
    CapState stateOf(const std::string& id) const; // RR when absent
};

class RuntimeProbe {
  public:
    // Collects the snapshot. Pure + deterministic for a given build; any
    // device-dependent upgrade (e.g. MC string) is applied explicitly via
    // setMinecraftVersion(), never guessed.
    static ProbeReport collect(const std::string& xykellVersion,
                               const std::string& leviPin,
                               const std::string& preloaderPin);
};

// Gate: a module may enable only when every requirement is Verified or
// Partial. Anything else (RR/Blocked/NI/absent) blocks with the reason.
struct GateResult {
    bool allowed = false;
    std::string reason;
};

GateResult canEnable(const std::vector<std::string>& needed,
                     const ProbeReport& probe, bool quarantined);

} // namespace xykell::runtime
