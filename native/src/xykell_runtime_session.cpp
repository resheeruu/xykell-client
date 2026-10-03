// Runtime session + game-state + feature-gate implementation.
// No I/O, no threads, no randomness: fully deterministic and host-testable.
#include "xykell/runtime_session.h"

namespace xykell::runtime {

namespace {

constexpr const char* kNoAttachment =
    "NOT CONNECTED: no proven runtime attachment mechanism (launch is not "
    "connection; see stage7-runtime-attachment.md)";

} // namespace

GameState GameState::unattached() {
    GameState g;
    g.playerPresence.availability = Availability::Unavailable;
    g.worldPresence.availability = Availability::Unavailable;
    g.dimension.availability = Availability::Unavailable;
    g.position.availability = Availability::Unavailable;
    g.rotation.availability = Availability::Unavailable;
    g.health.availability = Availability::Unavailable;
    g.screen.availability = Availability::Unavailable;
    g.entityCount.availability = Availability::Unavailable;
    return g;  // values stay empty: no fabricated health/position/dimension
}

SessionManager::SessionManager() = default;

RuntimeSession SessionManager::begin(const std::string& minecraftPackage,
                                     const std::string& minecraftVersion,
                                     const std::string& providerName) {
    ++sessionsStarted_;
    session_ = RuntimeSession();
    session_.sessionId = "xykell-session-" + std::to_string(sessionsStarted_);
    session_.minecraftPackage = minecraftPackage;
    session_.minecraftVersion = minecraftVersion;
    session_.providerName = providerName;
    session_.diagnostics = kNoAttachment;
    session_.gameState = GameState::unattached();
    active_ = true;
    return session_;
}

void SessionManager::markLaunched() {
    if (!active_) return;
    session_.launched = true;
    // launched records the system intent only; connected is untouched.
}

void SessionManager::end(const std::string& reason) {
    if (!active_) return;
    active_ = false;
    session_.diagnostics = reason.empty() ? kNoAttachment : reason;
}

FeatureAvailability availabilityFor(bool needsRuntime, bool runtimeConnected,
                                    bool demonstrated) {
    if (demonstrated) return FeatureAvailability::Available;
    if (needsRuntime && !runtimeConnected) return FeatureAvailability::RuntimeRequired;
    if (needsRuntime) return FeatureAvailability::Unknown;  // connected but unproven
    return FeatureAvailability::Available;  // launcher-local, no runtime needed
}

} // namespace xykell::runtime
