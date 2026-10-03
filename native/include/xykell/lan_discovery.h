#pragma once

// Real LAN endpoint discovery (Stage 6). Legitimate local-network metadata
// only: endpoint identity + service descriptor + TTL. No gameplay traffic,
// no Minecraft protocol, no impersonation: the service name is Xykell's own
// ("xykell-lan") and advertisements never claim Minecraft compatibility.
//
// READ-ONLY character is preserved: this layer discovers and selects
// endpoints; it cannot transmit gameplay state. See the structural audit.
#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

namespace xykell::discovery {

inline constexpr const char* kServiceName = "xykell-lan";
inline constexpr int kServiceVersion = 1;

enum class DiscoveryCode : std::uint8_t {
    Ok = 0,
    NetworkUnavailable,
    BindFailed,
    InvalidAdvertisement,
    UnsupportedVersion,
    EndpointExpired,
    Stopped,
};

// Wire format (UDP payload, ASCII, bounded):
//   XYKELL-ADV/1;id=<id>;addr=<addr>;port=<port>;proto=<n>;caps=<csv>;ttl=<ms>
// All fields mandatory; unknown service versions rejected; overlong or
// malformed datagrams rejected. No authentication material exists here.
struct Advertisement {
    std::string endpointId;
    std::string address;
    std::uint16_t port = 0;
    int protocolVersion = 0;
    std::string capabilityFlags;
    std::uint64_t ttlMs = 0;

    std::string serialize(const std::string& service) const;
    static std::optional<Advertisement> parse(const std::string& datagram);
};

struct DiscoveryConfig {
    std::string endpointId = "xykell-endpoint";
    std::string serviceName = kServiceName;
    int serviceVersion = kServiceVersion;
    std::string announceAddress = "127.0.0.1";  // loopback default; LAN opt-in
    std::uint16_t announcePort = 0;             // 0 = no announce target
    std::uint16_t listenPort = 0;               // 0 = ephemeral
    std::uint64_t announceIntervalMs = 1000;
    std::uint64_t defaultTtlMs = 5000;
    std::uint64_t protocolVersion = 1;
    std::string capabilityFlags = "DISCOVERY,ENDPOINT_STATUS";
};

struct EndpointRecord {
    Advertisement ad;
    std::string serviceName;
    std::uint64_t lastSeenMs = 0;
    bool selected = false;
};

// Local discovery engine: announce worker + receive worker, TTL expiry,
// duplicate suppression (same id refreshes liveness, never duplicates),
// deterministic selection (explicit id; listing sorted by id).
class LanDiscovery {
  public:
    LanDiscovery();
    ~LanDiscovery();

    LanDiscovery(const LanDiscovery&) = delete;
    LanDiscovery& operator=(const LanDiscovery&) = delete;

    // Starts workers. False with code set on bind failure; never throws.
    bool start(const DiscoveryConfig& cfg);
    void stop();  // idempotent; joins workers; no callbacks after return
    bool running() const { return running_.load(); }

    DiscoveryCode lastCode() const;
    std::string lastError() const;

    // Registry views (pruned of expired on read).
    std::vector<EndpointRecord> endpoints() const;
    bool selectEndpoint(const std::string& id);  // false when unknown/expired
    std::string selectedEndpoint();
    // Test/maintenance hooks (also used for TTL unit tests).
    void setManualTimeMs(std::uint64_t ms) { manualTimeMs_ = ms; }
    std::size_t pruneExpired();

    // Pure-function entry points (also used by unit tests).
    static bool ingestDatagram(const std::string& datagram, std::uint64_t nowMs,
                               std::vector<EndpointRecord>& registry,
                               const std::string& wantService, int wantVersion,
                               DiscoveryCode* codeOut);

  private:
    std::uint64_t nowMs() const;
    void announceLoop();
    void receiveLoop();

    DiscoveryConfig cfg_;
    std::atomic<bool> running_{false};
    std::thread announceThread_;
    std::thread receiveThread_;
    int recvFd_ = -1;
    mutable std::mutex mutex_;
    std::vector<EndpointRecord> registry_;
    std::string selected_;
    DiscoveryCode code_ = DiscoveryCode::Ok;
    std::string error_;
    std::uint64_t manualTimeMs_ = 0;
};

} // namespace xykell::discovery
