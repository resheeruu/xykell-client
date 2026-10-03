// LAN discovery implementation. POSIX UDP; bounded datagrams; workers join
// on stop. All failures become structured DiscoveryCode diagnostics.
#include "xykell/lan_discovery.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <chrono>
#include <cstring>

namespace xykell::discovery {

namespace {

constexpr std::size_t kMaxDatagram = 512;

std::uint64_t wallMs() {
    using namespace std::chrono;
    return static_cast<std::uint64_t>(
        duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count());
}

std::string field(const std::string& body, const char* key) {
    const std::string k = std::string(key) + "=";
    const std::size_t at = body.find(k);
    if (at == std::string::npos) return {};
    const std::size_t start = at + k.size();
    const std::size_t end = body.find(';', start);
    return body.substr(start, end == std::string::npos ? end : end - start);
}

} // namespace

std::string Advertisement::serialize(const std::string& service) const {
    return service + "/1;id=" + endpointId + ";addr=" + address + ";port=" +
           std::to_string(port) + ";proto=" + std::to_string(protocolVersion) +
           ";caps=" + capabilityFlags + ";ttl=" + std::to_string(ttlMs);
}

std::optional<Advertisement> Advertisement::parse(const std::string& datagram) {
    if (datagram.size() > kMaxDatagram) return std::nullopt;
    const std::size_t slash = datagram.find('/');
    const std::size_t semi = datagram.find(';');
    if (slash == std::string::npos || semi == std::string::npos || semi < slash) {
        return std::nullopt;
    }
    const std::string service = datagram.substr(0, slash);
    const std::string version = datagram.substr(slash + 1, semi - slash - 1);
    if (service != kServiceName || version != "1") return std::nullopt;
    const std::string body = datagram.substr(semi + 1);
    Advertisement ad;
    ad.endpointId = field(body, "id");
    ad.address = field(body, "addr");
    const std::string port = field(body, "port");
    const std::string proto = field(body, "proto");
    ad.capabilityFlags = field(body, "caps");
    const std::string ttl = field(body, "ttl");
    if (ad.endpointId.empty() || ad.address.empty() || port.empty() ||
        proto.empty() || ttl.empty()) {
        return std::nullopt;
    }
    try {
        const int p = std::stoi(port);
        const int v = std::stoi(proto);
        const long t = std::stol(ttl);
        if (p <= 0 || p > 65535 || v <= 0 || t <= 0) return std::nullopt;
        ad.port = static_cast<std::uint16_t>(p);
        ad.protocolVersion = v;
        ad.ttlMs = static_cast<std::uint64_t>(t);
    } catch (...) {
        return std::nullopt;
    }
    return ad;
}

LanDiscovery::LanDiscovery() = default;

LanDiscovery::~LanDiscovery() { stop(); }

std::uint64_t LanDiscovery::nowMs() const {
    return manualTimeMs_ != 0 ? manualTimeMs_ : wallMs();
}

bool LanDiscovery::ingestDatagram(const std::string& datagram, std::uint64_t nowMs,
                                  std::vector<EndpointRecord>& registry,
                                  const std::string& wantService, int wantVersion,
                                  DiscoveryCode* codeOut) {
    auto setCode = [&](DiscoveryCode c) {
        if (codeOut != nullptr) *codeOut = c;
    };
    const auto parsed = Advertisement::parse(datagram);
    if (!parsed) {
        setCode(DiscoveryCode::InvalidAdvertisement);
        return false;
    }
    if (wantService != kServiceName) {
        setCode(DiscoveryCode::InvalidAdvertisement);
        return false;
    }
    if (parsed->protocolVersion != wantVersion) {
        setCode(DiscoveryCode::UnsupportedVersion);
        return false;
    }
    for (auto& r : registry) {
        if (r.ad.endpointId == parsed->endpointId) {
            r.ad = *parsed;  // refresh, never duplicate
            r.lastSeenMs = nowMs;
            setCode(DiscoveryCode::Ok);
            return true;
        }
    }
    EndpointRecord rec;
    rec.ad = *parsed;
    rec.serviceName = wantService;
    rec.lastSeenMs = nowMs;
    registry.push_back(std::move(rec));
    setCode(DiscoveryCode::Ok);
    return true;
}

bool LanDiscovery::start(const DiscoveryConfig& cfg) {
    if (running_.load()) return false;  // no duplicate workers
    cfg_ = cfg;
    recvFd_ = ::socket(AF_INET, SOCK_DGRAM, 0);
    if (recvFd_ < 0) {
        code_ = DiscoveryCode::NetworkUnavailable;
        error_ = "socket() failed";
        return false;
    }
    int reuse = 1;
    ::setsockopt(recvFd_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(cfg_.listenPort);
    if (::bind(recvFd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        ::close(recvFd_);
        recvFd_ = -1;
        code_ = DiscoveryCode::BindFailed;
        error_ = "bind() failed";
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        registry_.clear();
        selected_.clear();
        code_ = DiscoveryCode::Ok;
        error_.clear();
    }
    running_.store(true);
    announceThread_ = std::thread(&LanDiscovery::announceLoop, this);
    receiveThread_ = std::thread(&LanDiscovery::receiveLoop, this);
    return true;
}

void LanDiscovery::stop() {
    if (!running_.exchange(false)) return;  // idempotent
    if (recvFd_ >= 0) {
        ::shutdown(recvFd_, SHUT_RDWR);
        ::close(recvFd_);
        recvFd_ = -1;
    }
    if (announceThread_.joinable()) announceThread_.join();
    if (receiveThread_.joinable()) receiveThread_.join();
    std::lock_guard<std::mutex> lock(mutex_);
    registry_.clear();
    selected_.clear();
    code_ = DiscoveryCode::Stopped;
}

DiscoveryCode LanDiscovery::lastCode() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return code_;
}

std::string LanDiscovery::lastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return error_;
}

std::vector<EndpointRecord> LanDiscovery::endpoints() const {
    std::lock_guard<std::mutex> lock(mutex_);
    const std::uint64_t now = nowMs();
    std::vector<EndpointRecord> out;
    for (const auto& r : registry_) {
        if (now < r.lastSeenMs + r.ad.ttlMs) out.push_back(r);
    }
    return out;
}

bool LanDiscovery::selectEndpoint(const std::string& id) {
    std::lock_guard<std::mutex> lock(mutex_);
    const std::uint64_t now = nowMs();
    for (const auto& r : registry_) {
        if (r.ad.endpointId == id && now < r.lastSeenMs + r.ad.ttlMs) {
            selected_ = id;
            return true;
        }
    }
    return false;
}

std::string LanDiscovery::selectedEndpoint() {
    std::lock_guard<std::mutex> lock(mutex_);
    return selected_;
}

std::size_t LanDiscovery::pruneExpired() {
    std::lock_guard<std::mutex> lock(mutex_);
    const std::uint64_t now = nowMs();
    std::size_t removed = 0;
    for (auto it = registry_.begin(); it != registry_.end();) {
        if (!(now < it->lastSeenMs + it->ad.ttlMs)) {
            if (it->ad.endpointId == selected_) selected_.clear();
            it = registry_.erase(it);
            ++removed;
        } else {
            ++it;
        }
    }
    return removed;
}

void LanDiscovery::announceLoop() {
    const int fd = ::socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return;
    int broadcast = 1;
    ::setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &broadcast, sizeof(broadcast));
    Advertisement ad;
    ad.endpointId = cfg_.endpointId;
    ad.address = cfg_.announceAddress;
    ad.port = cfg_.listenPort;
    ad.protocolVersion = static_cast<int>(cfg_.protocolVersion);
    ad.capabilityFlags = cfg_.capabilityFlags;
    ad.ttlMs = cfg_.defaultTtlMs;
    const std::string payload = ad.serialize(cfg_.serviceName);
    sockaddr_in dst{};
    dst.sin_family = AF_INET;
    dst.sin_port = htons(cfg_.announcePort);
    ::inet_pton(AF_INET, cfg_.announceAddress.c_str(), &dst.sin_addr);
    while (running_.load()) {
        if (cfg_.announcePort != 0) {
            ::sendto(fd, payload.data(), payload.size(), 0,
                     reinterpret_cast<sockaddr*>(&dst), sizeof(dst));
        }
        for (std::uint64_t waited = 0;
             waited < cfg_.announceIntervalMs && running_.load(); waited += 50) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
    }
    ::close(fd);
}

void LanDiscovery::receiveLoop() {
    char buf[kMaxDatagram + 1];
    while (running_.load()) {
        sockaddr_in src{};
        socklen_t len = sizeof(src);
        const ssize_t n =
            ::recvfrom(recvFd_, buf, kMaxDatagram, 0, reinterpret_cast<sockaddr*>(&src), &len);
        if (n <= 0) {
            if (!running_.load()) break;
            continue;
        }
        const std::string datagram(buf, static_cast<std::size_t>(n));
        std::lock_guard<std::mutex> lock(mutex_);
        DiscoveryCode code = DiscoveryCode::Ok;
        std::vector<EndpointRecord> reg = registry_;
        // Ingest into a copy under the same lock scope, then commit, so the
        // registry is never observed half-updated by endpoints()/select.
        if (ingestDatagram(datagram, nowMs(), reg, cfg_.serviceName,
                            static_cast<int>(cfg_.protocolVersion), &code)) {
            registry_ = std::move(reg);
            code_ = DiscoveryCode::Ok;
        } else if (code != DiscoveryCode::Ok) {
            code_ = code;
        }
    }
}

} // namespace xykell::discovery
