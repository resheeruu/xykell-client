// Shared runtime substrate implementation. Deterministic, synchronous,
// in-memory: the synthetic provider models lifecycle only -- it opens no
// sockets and emits fixed synthetic observations.
#include "xykell/runtime_provider.h"

#include <chrono>

namespace xykell::runtime {

namespace {

std::uint64_t wallMs() {
    using namespace std::chrono;
    return static_cast<std::uint64_t>(
        duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count());
}

constexpr const char* kSyntheticPlayer = "synthetic-player";
constexpr const char* kSyntheticWorld = "synthetic-world";
constexpr const char* kSyntheticEntity = "synthetic-entity";

} // namespace

std::uint64_t SyntheticRelayProvider::nowMs() const {
    return manualTimeMs_ != 0 ? manualTimeMs_ : wallMs();
}

bool SyntheticRelayProvider::start() {
    if (state_ == ProviderState::Running) return false;  // no duplicate starts
    if (state_ == ProviderState::Starting) return false;
    state_ = ProviderState::Starting;
    startedAtMs_ = nowMs();
    lastError_.clear();
    // Two deterministic endpoints (Stage-3B model); loopback-shaped data.
    endpoints_.clear();
    endpoints_.push_back(EndpointDescriptor{"synthetic-a", "Synthetic A",
                                            "127.0.0.1", 19132,
                                            {"player-observation", "world-observation"},
                                            nowMs(), 0});
    endpoints_.push_back(EndpointDescriptor{"synthetic-b", "Synthetic B",
                                            "127.0.0.1", 19133,
                                            {"entity-observation", "chat-observation"},
                                            nowMs(), 0});
    state_ = ProviderState::Running;
    if (sink_ != nullptr) {
        sink_->onConnection(ConnectionObservation{true, "in-memory", nowMs()});
    }
    return true;
}

void SyntheticRelayProvider::stop() {
    if (state_ == ProviderState::Stopped) return;  // idempotent
    state_ = ProviderState::Stopping;
    if (sink_ != nullptr) {
        sink_->onConnection(ConnectionObservation{false, "in-memory", nowMs()});
    }
    endpoints_.clear();
    sessionsTotal_ = 0;
    state_ = ProviderState::Stopped;
}

std::vector<CapabilityInfo> SyntheticRelayProvider::capabilities() const {
    const std::string p = name();
    return {
        {CapabilityKind::Connection, "connection", p},
        {CapabilityKind::Session, "session", p},
        {CapabilityKind::ServerInfo, "server-info", p},
        {CapabilityKind::PlayerObservation, "player-observation", p},
        {CapabilityKind::EntityObservation, "entity-observation", p},
        {CapabilityKind::WorldObservation, "world-observation", p},
        {CapabilityKind::InventoryObservation, "inventory-observation", p},
        {CapabilityKind::ChatObservation, "chat-observation", p},
        {CapabilityKind::TransportDiagnostics, "transport-diagnostics", p},
    };
}

DiagnosticInfo SyntheticRelayProvider::diagnostics() const {
    DiagnosticInfo d;
    d.provider = name();
    d.state = state_;
    d.lastError = lastError_;
    d.uptimeMs = (state_ == ProviderState::Running && startedAtMs_ != 0)
                     ? nowMs() - startedAtMs_
                     : 0;
    d.sessionsActive = 0;  // synthetic sessions close synchronously
    d.sessionsTotal = sessionsTotal_;
    return d;
}

void SyntheticRelayProvider::advertise(EndpointDescriptor ep) {
    ep.advertisedAtMs = nowMs();
    for (auto& e : endpoints_) {
        if (e.id == ep.id) {
            e = ep;
            return;
        }
    }
    endpoints_.push_back(std::move(ep));
}

void SyntheticRelayProvider::withdraw(const std::string& id) {
    for (auto it = endpoints_.begin(); it != endpoints_.end(); ++it) {
        if (it->id == id) {
            endpoints_.erase(it);
            return;
        }
    }
}

std::vector<EndpointDescriptor> SyntheticRelayProvider::discover() const {
    const std::uint64_t now = nowMs();
    std::vector<EndpointDescriptor> out;
    for (const auto& e : endpoints_) {
        if (e.available(now)) out.push_back(e);
    }
    return out;
}

bool SyntheticRelayProvider::openSession(const std::string& endpointId,
                                         std::string* sessionId) {
    if (state_ != ProviderState::Running) {
        lastError_ = "provider not running";
        return false;
    }
    const std::uint64_t now = nowMs();
    bool known = false;
    for (const auto& e : endpoints_) {
        if (e.id == endpointId && e.available(now)) {
            known = true;
            break;
        }
    }
    if (!known) {
        lastError_ = "unknown or expired endpoint: " + endpointId;
        return false;
    }
    ++sessionSeq_;
    ++sessionsTotal_;
    const std::string sid = "synthetic-session-" + std::to_string(sessionSeq_);
    if (sessionId != nullptr) *sessionId = sid;
    if (sink_ != nullptr) {
        // Deterministic synthetic observations (fixed ids, live timestamps).
        sink_->onSession(SessionObservation{sid, endpointId, true, 2, 2, 128, 128, now});
        sink_->onPlayer(PlayerObservation{kSyntheticPlayer, kSyntheticWorld, now});
        sink_->onWorld(WorldObservation{kSyntheticWorld, now});
        sink_->onEntity(EntityObservation{kSyntheticEntity, kSyntheticWorld, now});
        sink_->onChat(ChatObservation{"synthetic", "synthetic hello", now});
        sink_->onSession(SessionObservation{sid, endpointId, false, 2, 2, 128, 128, now});
    }
    return true;
}

bool SyntheticRelayProvider::closeSession(const std::string& sessionId) {
    if (state_ != ProviderState::Running) return false;
    return !sessionId.empty();  // synthetic sessions are synchronous records
}

void LanDiscoveryProvider::setConfig(const discovery::DiscoveryConfig& cfg) {
    config_ = cfg;
}

bool LanDiscoveryProvider::start() {
    if (state_ == ProviderState::Running || state_ == ProviderState::Starting) {
        return false;  // no duplicate starts
    }
    state_ = ProviderState::Starting;
    lastError_.clear();
    if (!discovery_.start(config_)) {
        lastError_ = discovery_.lastError().empty() ? "discovery failed to start"
                                                    : discovery_.lastError();
        state_ = ProviderState::Failed;
        return false;
    }
    startedAtMs_ = wallMs();
    state_ = ProviderState::Running;
    if (sink_ != nullptr) {
        sink_->onConnection(ConnectionObservation{true, "udp-loopback", startedAtMs_});
    }
    return true;
}

void LanDiscoveryProvider::stop() {
    if (state_ == ProviderState::Stopped) return;  // idempotent
    state_ = ProviderState::Stopping;
    discovery_.stop();
    if (sink_ != nullptr) {
        sink_->onConnection(ConnectionObservation{false, "udp-loopback", wallMs()});
    }
    state_ = ProviderState::Stopped;
}

std::vector<CapabilityInfo> LanDiscoveryProvider::capabilities() const {
    const std::string p = name();
    // Discovery metadata only: no game-state observations offered.
    return {
        {CapabilityKind::Connection, "connection", p},
        {CapabilityKind::Session, "session", p},
        {CapabilityKind::ServerInfo, "server-info", p},
        {CapabilityKind::TransportDiagnostics, "transport-diagnostics", p},
    };
}

DiagnosticInfo LanDiscoveryProvider::diagnostics() const {
    DiagnosticInfo d;
    d.provider = name();
    d.state = state_;
    if (state_ == ProviderState::Failed && lastError_.empty()) {
        d.lastError = "discovery failed";
    } else {
        d.lastError = lastError_;
    }
    if (state_ == ProviderState::Failed && d.lastError.empty()) {
        const std::string dl = discovery_.lastError();
        if (!dl.empty()) d.lastError = dl;
    }
    d.uptimeMs = (state_ == ProviderState::Running) ? wallMs() - startedAtMs_ : 0;
    d.sessionsActive = discovery_.endpoints().size();
    return d;
}

DiagnosticInfo NativeProviderStub::diagnostics() const {    DiagnosticInfo d;
    d.provider = name();
    d.state = state_;
    d.lastError = (state_ == ProviderState::Failed) ? kUnavailableReason : "";
    return d;
}

Runtime::Runtime() : provider_(std::make_unique<SyntheticRelayProvider>()) {}

bool Runtime::selectProvider(const std::string& name) {
    if (name == SyntheticRelayProvider::kName) {
        if (state() == ProviderState::Running) return false;  // no hot-swap
        provider_ = std::make_unique<SyntheticRelayProvider>();
        provider_->setSink(sink_);
        selected_ = name;
        selectedEndpoint_.clear();
        lastError_.clear();
        return true;
    }
    if (name == NativeProviderStub::kName) {
        if (state() == ProviderState::Running) return false;
        provider_ = std::make_unique<NativeProviderStub>();
        provider_->setSink(sink_);
        selected_ = name;
        selectedEndpoint_.clear();
        lastError_.clear();
        return true;
    }
    if (name == LanDiscoveryProvider::kName) {
        if (state() == ProviderState::Running) return false;
        provider_ = std::make_unique<LanDiscoveryProvider>();
        provider_->setSink(sink_);
        selected_ = name;
        selectedEndpoint_.clear();
        lastError_.clear();
        return true;
    }
    return false;  // unknown name: selection unchanged, fail safely
}

bool Runtime::start() {
    const ProviderState s = provider_->state();
    if (s == ProviderState::Running || s == ProviderState::Starting) return false;
    if (s == ProviderState::Stopping) return false;
    if (!provider_->start()) {
        lastError_ = provider_->diagnostics().lastError;
        return false;
    }
    return true;
}

void Runtime::stop() {
    provider_->stop();  // idempotent at provider level; no sink calls after
}

ProviderState Runtime::state() const { return provider_->state(); }

DiagnosticInfo Runtime::diagnostics() const { return provider_->diagnostics(); }

std::vector<CapabilityInfo> Runtime::capabilities() const {
    return provider_->capabilities();
}

std::vector<EndpointDescriptor> Runtime::discover() const {
    if (selected_ == SyntheticRelayProvider::kName) {
        const auto* p = static_cast<const SyntheticRelayProvider*>(provider_.get());
        return p->discover();
    }
    if (selected_ == LanDiscoveryProvider::kName) {
        const auto* p = static_cast<const LanDiscoveryProvider*>(provider_.get());
        std::vector<EndpointDescriptor> out;
        for (const auto& r : p->discovery().endpoints()) {
            EndpointDescriptor e;
            e.id = r.ad.endpointId;
            e.displayName = r.serviceName + "/" + r.ad.endpointId;
            e.address = r.ad.address;
            e.port = r.ad.port;
            e.capabilities = {"discovery", "endpoint-status"};
            e.advertisedAtMs = r.lastSeenMs;
            e.ttlMs = r.ad.ttlMs;
            out.push_back(std::move(e));
        }
        return out;
    }
    return {};
}

bool Runtime::selectEndpoint(const std::string& id) {    if (selected_ == SyntheticRelayProvider::kName) {
        auto* p = static_cast<SyntheticRelayProvider*>(provider_.get());
        for (const auto& e : p->discover()) {
            if (e.id == id) {
                selectedEndpoint_ = id;
                return true;
            }
        }
        return false;
    }
    if (selected_ == LanDiscoveryProvider::kName) {
        auto* p = static_cast<LanDiscoveryProvider*>(provider_.get());
        if (p->discovery().selectEndpoint(id)) {
            selectedEndpoint_ = id;
            return true;
        }
        return false;
    }
    return false;
}

RuntimeSession Runtime::beginSession(const std::string& minecraftPackage,
                                     const std::string& minecraftVersion) {
    return sessions_.begin(minecraftPackage, minecraftVersion, selected_);
}

void Runtime::markSessionLaunched() { sessions_.markLaunched(); }

void Runtime::endSession(const std::string& reason) { sessions_.end(reason); }

} // namespace xykell::runtime
