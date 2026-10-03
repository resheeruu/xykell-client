#pragma once

// Xykell-owned Minecraft runtime session + read-only game-state foundation
// (Stage 7). Pure C++; no Android, no game, no network, no authentication.
//
// TRUTH RULE: `connected` is false unless a proven attachment mechanism
// sets it. Nothing in this stage sets it: launching Minecraft (a system
// intent, owned by the Android layer) is NOT a runtime connection, and a
// packaged native library is NOT an executing one. The model makes those
// confusions unrepresentable: launch state and connection state are
// separate fields, and connection has no setter except a proven provider.
#include <cstdint>
#include <string>

namespace xykell::runtime {

// Availability of a single game-state field. No fabricated defaults: a
// field that is not AVAILABLE carries no usable value.
enum class Availability : std::uint8_t {
    Unknown = 0,  // never probed (e.g. no session yet)
    Unavailable,  // probed or known-absent (no attachment mechanism)
    Available,    // observed through a proven provider path
};

struct GameField {
    Availability availability = Availability::Unknown;
    std::string value;  // meaningful ONLY when availability == Available
};

struct GameState {
    GameField playerPresence;
    GameField worldPresence;
    GameField dimension;
    GameField position;
    GameField rotation;
    GameField health;
    GameField screen;
    GameField entityCount;

    static GameState unattached();  // all fields Unavailable, values empty
};

// Runtime session: one Xykell-owned launch/monitor cycle. Deterministic ids
// ("xykell-session-<n>"); uniqueness is per-process sequence, never random,
// never derived from account/device identity.
struct RuntimeSession {
    std::string sessionId;
    std::string minecraftPackage;
    std::string minecraftVersion;
    std::string providerName;
    bool launched = false;    // system launch intent fired (Android layer)
    bool connected = false;   // runtime attachment proven (nothing sets it yet)
    std::string diagnostics;  // exact blocker/reason, never a fake state
    GameState gameState = GameState::unattached();
};

class SessionManager {
  public:
    SessionManager();

    // Begins a session for an Xykell-owned launch. Never claims connection:
    // connected stays false with the attachment blocker recorded.
    RuntimeSession begin(const std::string& minecraftPackage,
                         const std::string& minecraftVersion,
                         const std::string& providerName);
    void markLaunched();
    void end(const std::string& reason);
    const RuntimeSession& current() const { return session_; }
    bool active() const { return active_; }
    std::uint64_t sessionsStarted() const { return sessionsStarted_; }

  private:
    RuntimeSession session_;
    bool active_ = false;
    std::uint64_t sessionsStarted_ = 0;
};

// Feature capability bridge: registry status x runtime reality -> honest
// availability. Pure logic; the registry stays the source of REGISTERED.
enum class FeatureAvailability : std::uint8_t {
    Registered = 0,  // known to the registry, runtime state irrelevant yet
    Available,       // demonstrated end-to-end
    RuntimeRequired, // needs a connected runtime (not present in Stage 7)
    Partial,         // partly demonstrated
    Unavailable,     // proven absent
    Unknown,         // never assessed
};

inline const char* toString(FeatureAvailability a) {
    switch (a) {
        case FeatureAvailability::Registered: return "REGISTERED";
        case FeatureAvailability::Available: return "AVAILABLE";
        case FeatureAvailability::RuntimeRequired: return "RUNTIME_REQUIRED";
        case FeatureAvailability::Partial: return "PARTIAL";
        case FeatureAvailability::Unavailable: return "UNAVAILABLE";
        case FeatureAvailability::Unknown: return "UNKNOWN";
    }
    return "UNKNOWN";
}

// Anything implying live game data requires a connected runtime. Since no
// session in Stage 7 is ever connected, gameplay features gate to
// RUNTIME_REQUIRED and launcher-local features stay AVAILABLE.
FeatureAvailability availabilityFor(bool needsRuntime, bool runtimeConnected,
                                    bool demonstrated);

} // namespace xykell::runtime
