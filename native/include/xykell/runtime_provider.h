#pragma once

// Shared runtime substrate: provider abstraction + lifecycle + read-only
// capability events. Pure C++ (host-testable); no Android, no preloader,
// no game headers, no sockets, no Minecraft protocol.
//
// HARD RULE (Stage 4): the provider contract exposes observation only.
// There is deliberately NO send/write/inject/forge API anywhere in this
// file. A provider that needs gameplay writes cannot exist behind this
// interface -- it would require a different type, which is the point.
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "xykell/lan_discovery.h"
#include "xykell/runtime_session.h"

namespace xykell::runtime {

enum class ProviderState : std::uint8_t {
    Stopped = 0,
    Starting,
    Running,
    Stopping,
    Failed,
};

inline const char* toString(ProviderState s) {
    switch (s) {
        case ProviderState::Stopped: return "STOPPED";
        case ProviderState::Starting: return "STARTING";
        case ProviderState::Running: return "RUNNING";
        case ProviderState::Stopping: return "STOPPING";
        case ProviderState::Failed: return "FAILED";
    }
    return "UNKNOWN";
}

// Read-only capability kinds. Every kind is an observation; none implies
// any ability to change game state.
enum class CapabilityKind : std::uint8_t {
    Connection = 0,
    Session,
    ServerInfo,
    PlayerObservation,
    EntityObservation,
    WorldObservation,
    InventoryObservation,
    ChatObservation,
    TransportDiagnostics,
};

struct CapabilityInfo {
    CapabilityKind kind;
    std::string name;      // stable identifier, e.g. "player-observation"
    std::string provider;  // provider name that offers it
};

// Advertised endpoint (synthetic model of Stage-3B discovery; no LAN I/O).
struct EndpointDescriptor {
    std::string id;           // stable identity, e.g. "synthetic-a"
    std::string displayName;  // human label; never a production identity
    std::string address;      // loopback/test only in Stage 4
    std::uint16_t port = 0;
    std::vector<std::string> capabilities;
    std::uint64_t advertisedAtMs = 0;
    std::uint64_t ttlMs = 0;  // 0 = no expiry

    bool available(std::uint64_t nowMs) const {
        if (ttlMs == 0) return true;
        return nowMs < advertisedAtMs + ttlMs;
    }
};

// Immutable observation snapshots. Plain data: no mutators, no handles
// back into provider state. Timestamps are provider-clock millis.
struct ConnectionObservation {
    bool connected = false;
    std::string transport;  // e.g. "in-memory"
    std::uint64_t atMs = 0;
};

struct SessionObservation {
    std::string sessionId;
    std::string endpointId;
    bool active = false;
    std::uint64_t messagesIn = 0;
    std::uint64_t messagesOut = 0;
    std::uint64_t bytesIn = 0;
    std::uint64_t bytesOut = 0;
    std::uint64_t atMs = 0;
};

struct EntityObservation {
    std::string entityId;
    std::string worldId;
    std::uint64_t atMs = 0;
};

struct WorldObservation {
    std::string worldId;
    std::uint64_t atMs = 0;
};

struct PlayerObservation {
    std::string playerId;
    std::string worldId;
    std::uint64_t atMs = 0;
};

struct ChatObservation {
    std::string channel;
    std::string text;
    std::uint64_t atMs = 0;
};

struct DiagnosticInfo {
    std::string provider;
    ProviderState state = ProviderState::Stopped;
    std::string lastError;
    std::uint64_t uptimeMs = 0;
    std::uint64_t sessionsActive = 0;
    std::uint64_t sessionsTotal = 0;
};

// Consumer sink for read-only capability data. Payloads are passed by
// const reference and must be treated as immutable snapshots.
struct CapabilitySink {
    virtual ~CapabilitySink() = default;
    virtual void onConnection(const ConnectionObservation&) {}
    virtual void onSession(const SessionObservation&) {}
    virtual void onPlayer(const PlayerObservation&) {}
    virtual void onEntity(const EntityObservation&) {}
    virtual void onWorld(const WorldObservation&) {}
    virtual void onChat(const ChatObservation&) {}
};

// Provider contract: lifecycle + introspection only. NOTE the absence of
// any send/write/inject/forge API -- that absence is the security boundary.
class RuntimeProvider {
  public:
    virtual ~RuntimeProvider() = default;
    virtual const char* name() const = 0;
    virtual bool start() = 0;  // false => state() is Failed, see diagnostics()
    virtual void stop() = 0;   // idempotent; no events after return
    virtual ProviderState state() const = 0;
    virtual std::vector<CapabilityInfo> capabilities() const = 0;
    virtual DiagnosticInfo diagnostics() const = 0;
    virtual void setSink(CapabilitySink* sink) = 0;  // nullable; not owned
};

// Deterministic synthetic relay: models the Stage-3 loopback lifecycle
// (connect/session/observe/disconnect) with fixed in-memory data. Opens no
// sockets, contacts nothing, invents no accounts. Test seam only.
class SyntheticRelayProvider final : public RuntimeProvider {
  public:
    static constexpr const char* kName = "synthetic-relay";

    const char* name() const override { return kName; }
    bool start() override;
    void stop() override;
    ProviderState state() const override { return state_; }
    std::vector<CapabilityInfo> capabilities() const override;
    DiagnosticInfo diagnostics() const override;
    void setSink(CapabilitySink* sink) override { sink_ = sink; }

    // Endpoint model (Stage-3B): independent descriptors, TTL expiry.
    void advertise(EndpointDescriptor ep);
    void withdraw(const std::string& id);
    std::vector<EndpointDescriptor> discover() const;
    // Deterministic synthetic session drive for tests/integration.
    bool openSession(const std::string& endpointId, std::string* sessionId);
    bool closeSession(const std::string& sessionId);
    // Test-only clock override (0 = wall clock). Never affects production
    // behavior beyond deterministic tests.
    void setManualTimeMs(std::uint64_t ms) { manualTimeMs_ = ms; }

  private:
    std::uint64_t nowMs() const;
    ProviderState state_ = ProviderState::Stopped;
    CapabilitySink* sink_ = nullptr;
    std::vector<EndpointDescriptor> endpoints_;
    std::uint64_t sessionsTotal_ = 0;
    std::uint64_t sessionSeq_ = 0;
    std::uint64_t startedAtMs_ = 0;
    std::uint64_t manualTimeMs_ = 0;
    std::string lastError_;
};

// LAN discovery provider (Stage 6): local-network endpoint discovery only.
// Capabilities are discovery metadata (endpoint-status, runtime-status,
// diagnostics). It deliberately offers NO player/entity/world observations:
// discovery sees advertisements, not game state.
class LanDiscoveryProvider final : public RuntimeProvider {
  public:
    static constexpr const char* kName = "lan-discovery";

    const char* name() const override { return kName; }
    bool start() override;
    void stop() override;
    ProviderState state() const override { return state_; }
    std::vector<CapabilityInfo> capabilities() const override;
    DiagnosticInfo diagnostics() const override;
    void setSink(CapabilitySink* sink) override { sink_ = sink; }

    void setConfig(const discovery::DiscoveryConfig& cfg);
    discovery::LanDiscovery& discovery() { return discovery_; }
    const discovery::LanDiscovery& discovery() const { return discovery_; }

  private:
    ProviderState state_ = ProviderState::Stopped;
    CapabilitySink* sink_ = nullptr;
    discovery::LanDiscovery discovery_;
    discovery::DiscoveryConfig config_;
    std::uint64_t startedAtMs_ = 0;
    std::string lastError_;
};

// Native/game provider: NOT IMPLEMENTED (lab-gated). Present so selection
// code paths are honest: start() always fails with an explicit reason.
class NativeProviderStub final : public RuntimeProvider {
  public:
    static constexpr const char* kName = "native";
    static constexpr const char* kUnavailableReason =
        "UNAVAILABLE: native provider not implemented (lab-gated)";

    const char* name() const override { return kName; }
    bool start() override {
        state_ = ProviderState::Failed;
        return false;
    }
    void stop() override { state_ = ProviderState::Stopped; }
    ProviderState state() const override { return state_; }
    std::vector<CapabilityInfo> capabilities() const override { return {}; }
    DiagnosticInfo diagnostics() const override;
    void setSink(CapabilitySink* sink) override { sink_ = sink; }

  private:
    ProviderState state_ = ProviderState::Stopped;
    CapabilitySink* sink_ = nullptr;
};

// Substrate: owns one provider, enforces lifecycle, isolates errors.
// Lifecycle: STOPPED -> STARTING -> RUNNING (-> STOPPING -> STOPPED),
// any provider failure -> FAILED (launcher never crashes; see lastError()).
class Runtime {
  public:
    Runtime();
    // Selects "synthetic-relay" (default) or "native" (stub). Unknown names
    // fail safely (returns false, selection unchanged).
    bool selectProvider(const std::string& name);
    const std::string& selectedProviderName() const { return selected_; }
    // Endpoint selection (validated against live discovery; deterministic).
    bool selectEndpoint(const std::string& id);
    const std::string& selectedEndpoint() const { return selectedEndpoint_; }

    bool start();  // idempotent-safe: false when already running/failed
    void stop();   // idempotent; no sink calls after return
    ProviderState state() const;
    DiagnosticInfo diagnostics() const;
    const std::string& lastError() const { return lastError_; }
    std::vector<CapabilityInfo> capabilities() const;
    std::vector<EndpointDescriptor> discover() const;

    void setSink(CapabilitySink* sink) {
        sink_ = sink;
        if (provider_) provider_->setSink(sink);
    }
    RuntimeProvider* provider() { return provider_.get(); }

    // Xykell-owned session (Stage 7): launch/monitor bookkeeping. Never
    // implies runtime attachment; see runtime_session.h truth rule.
    RuntimeSession beginSession(const std::string& minecraftPackage,
                                const std::string& minecraftVersion);
    void markSessionLaunched();
    void endSession(const std::string& reason);
    const RuntimeSession& session() const { return sessions_.current(); }
    bool sessionActive() const { return sessions_.active(); }

    // Config key helpers (caller owns persistence via XykellConfig).
    static constexpr const char* kConfigSection = "runtime";
    static constexpr const char* kConfigKeyProvider = "provider";
    static constexpr const char* kDefaultProvider = "synthetic-relay";

  private:
    std::unique_ptr<RuntimeProvider> provider_;
    std::string selected_ = kDefaultProvider;
    std::string selectedEndpoint_;
    SessionManager sessions_;
    CapabilitySink* sink_ = nullptr;
    std::string lastError_;
};

} // namespace xykell::runtime
