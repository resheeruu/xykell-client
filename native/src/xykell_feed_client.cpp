#include "xykell/feed_client.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <chrono>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <optional>
#include <thread>

#include "xykell/json_min.h"

namespace xykell::feed {
namespace {

using runtime::ObservationConsumer;

const json::Value* field(const json::Object& obj, const char* key) {
    const auto it = obj.find(key);
    return it == obj.end() ? nullptr : &it->second;
}

std::uint64_t fieldMs(const json::Object& obj) {
    const json::Value* at = field(obj, "at");
    if (at == nullptr || !at->isNumber()) return 0;
    const double v = at->asNumber();
    if (v < 0.0) return 0;
    return static_cast<std::uint64_t>(v);
}

bool applyTravel(ObservationConsumer& c, const json::Object& obj) {
    for (const char* key : {"id", "at", "x", "y", "z", "yaw", "m", "method"}) {
        if (field(obj, key) == nullptr) return false;
    }
    const json::Value* id = field(obj, "id");
    const json::Value* x = field(obj, "x");
    const json::Value* y = field(obj, "y");
    const json::Value* z = field(obj, "z");
    const json::Value* yaw = field(obj, "yaw");
    const json::Value* m = field(obj, "m");
    const json::Value* method = field(obj, "method");
    if (!id->isString() || !x->isNumber() || !y->isNumber() || !z->isNumber() ||
        !yaw->isNumber() || !m->isNumber() || !method->isNumber()) {
        return false;
    }
    const double methodRaw = method->asNumber();
    if (methodRaw != static_cast<double>(static_cast<long long>(methodRaw))) return false;
    auto obs = runtime::makePlayerTravel(
        id->asString(""), fieldMs(obj),
        runtime::Vec3{x->asNumber(), y->asNumber(), z->asNumber()},
        yaw->asNumber(), m->asNumber(), static_cast<int>(methodRaw));
    if (!obs.has_value()) return false;
    c.consume(*obs);
    return true;
}

bool applyChat(ObservationConsumer& c, const json::Object& obj) {
    for (const char* key : {"id", "at", "sender", "msg"}) {
        if (field(obj, key) == nullptr) return false;
    }
    const json::Value* id = field(obj, "id");
    const json::Value* sender = field(obj, "sender");
    const json::Value* msg = field(obj, "msg");
    if (!id->isString() || !sender->isString() || !msg->isString()) return false;
    auto obs = runtime::makePlayerMessage(id->asString(""), fieldMs(obj),
                                           sender->asString(""), msg->asString(""));
    if (!obs.has_value()) return false;
    c.consume(*obs);
    return true;
}

// Optional int field: absent key OR a non-numeric value means "not observed",
// matching the -1 sentinel the JNI offer uses. Present-but-negative is
// rejected by the factory, so a hostile line cannot write a bogus health.
std::optional<int> optInt(const json::Object& obj, const char* key) {
    const json::Value* v = field(obj, key);
    if (v == nullptr || !v->isNumber()) return std::nullopt;
    const double d = v->asNumber();
    if (!std::isfinite(d)) return std::nullopt;
    return static_cast<int>(d);
}

bool applyVitals(ObservationConsumer& c, const json::Object& obj) {
    if (field(obj, "id") == nullptr) return false;
    const json::Value* id = field(obj, "id");
    if (!id->isString()) return false;
    auto obs = runtime::makeVitals(id->asString(""), fieldMs(obj), optInt(obj, "health"),
                                   optInt(obj, "ticks"));
    // Neither field present is an empty line: rejected, not stored.
    if (!obs.has_value()) return false;
    c.consume(*obs);
    return true;
}

bool applyPopulation(ObservationConsumer& c, const json::Object& obj) {
    for (const char* key : {"id", "at", "entities", "players"}) {
        if (field(obj, key) == nullptr) return false;
    }
    const json::Value* id = field(obj, "id");
    if (!id->isString()) return false;
    const auto e = optInt(obj, "entities");
    const auto pl = optInt(obj, "players");
    // Negative counts are rejected by the factory (the cast would wrap, so they
    // are refused here rather than becoming huge unsigned values).
    if (!e.has_value() || !pl.has_value() || *e < 0 || *pl < 0) return false;
    auto obs = runtime::makeEntityPopulation(id->asString(""), fieldMs(obj),
                                             static_cast<std::uint64_t>(*e),
                                             static_cast<std::uint64_t>(*pl));
    if (!obs.has_value()) return false;
    c.consume(*obs);
    return true;
}

int connectOnce(const char* host, int port) {
    const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<std::uint16_t>(port));
    if (::inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        ::close(fd);
        return -1;
    }
    if (::connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        ::close(fd);
        return -1;
    }
    timeval tv{};
    tv.tv_sec = 0;
    tv.tv_usec = kRecvTimeoutMs * 1000;
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    return fd;
}

// Reads lines from one connection; returns false when the connection is
// dead (caller reconnects). Partial lines stay in `buffer`.
bool readConnection(int fd, ObservationConsumer& c, std::string& buffer,
                    std::atomic<bool>& stop) {
    char chunk[2048];
    while (!stop) {
        const ssize_t n = ::recv(fd, chunk, sizeof(chunk), 0);
        if (n == 0) return false;      // orderly close
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) continue; // timeout tick
            return false;
        }
        buffer.append(chunk, static_cast<std::size_t>(n));
        std::size_t nl = 0;
        while ((nl = buffer.find('\n')) != std::string::npos) {
            std::string line = buffer.substr(0, nl);
            buffer.erase(0, nl + 1);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (!line.empty()) (void)applyLine(c, line); // malformed: skip
        }
        if (buffer.size() > 64 * 1024) buffer.clear(); // bounded
    }
    return false;
}

std::mutex gStartMutex;
std::thread gThread;
std::atomic<bool> gStop{true};

} // namespace

bool applyLine(ObservationConsumer& c, const std::string& line) {
    if (line.empty()) return false;
    const json::ParseResult pr = json::parse(line);
    if (!pr.ok || !pr.value.isObject()) return false;
    const auto& obj = pr.value.asObject(json::Value::emptyObject());
    const json::Value* t = field(obj, "t");
    if (t == nullptr || !t->isString()) return false;
    const std::string& type = t->asString("");
    if (type == "travel") return applyTravel(c, obj);
    if (type == "chat") return applyChat(c, obj);
    if (type == "vitals") return applyVitals(c, obj);
    if (type == "population") return applyPopulation(c, obj);
    return false;
}

void runLoop(ObservationConsumer& c, const char* host, int port,
             std::atomic<bool>& stop) {
    std::string buffer;
    while (!stop) {
        const int fd = connectOnce(host, port);
        if (fd < 0) {
            // Server down: bounded retry cadence, exit promptly on stop.
            for (int waited = 0; waited < kRetryDelayMs && !stop;
                 waited += 50) {
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
            }
            continue;
        }
        buffer.clear();
        (void)readConnection(fd, c, buffer, stop);
        ::close(fd);
    }
}

void start() {
    std::lock_guard<std::mutex> lock(gStartMutex);
    if (gThread.joinable()) return; // already running
    gStop = false;
    gThread = std::thread([&] {
        runLoop(runtime::sharedObservationConsumer(), kDefaultHost, kDefaultPort,
                gStop);
    });
}

void stop() {
    std::lock_guard<std::mutex> lock(gStartMutex);
    gStop = true;
    if (gThread.joinable()) gThread.join();
}

} // namespace xykell::feed
