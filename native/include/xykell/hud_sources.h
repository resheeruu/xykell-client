#pragma once

// Local-data HUD providers (Batch 1). Pure C++, no platform, no game, no
// network. Binds verified-local data (frame clock, tap stream, wall clock,
// session timer) to HudElement providers. Elements WITHOUT a verified
// source (coordinates, health, armor, target, ...) are left untouched and
// keep rendering kUnavailable ("--") — never fabricated.
//
// Also registers the first real toggleable modules (fps, cps, clock,
// session_stats, stop_watch): descriptors only, LOW risk, quarantine-safe.
// Keystrokes is deliberately NOT registered: it needs a tap-position
// stream that does not exist yet.
#include <cstdint>
#include <cstdio>
#include <functional>
#include <optional>
#include <string>
#include <vector>

#include "xykell/hud_model.h"
#include "xykell/module_manager.h"
#include "xykell/runtime_observation_consumer.h"

namespace xykell::hud::sources {

// Sliding 1000 ms window event rate. Timestamps are host milliseconds;
// fully deterministic under injected time (no clock calls here).
class WindowRate {
  public:
    void push(std::int64_t nowMs) {
        events_.push_back(nowMs);
        prune(nowMs);
    }

    // Events strictly after (nowMs - 1000).
    std::size_t count(std::int64_t nowMs) {
        prune(nowMs);
        return events_.size();
    }

    void clear() { events_.clear(); }

  private:
    void prune(std::int64_t nowMs) {
        while (!events_.empty() && events_.front() <= nowMs - 1000) {
            events_.erase(events_.begin());
        }
    }

    std::vector<std::int64_t> events_;
};

using FrameTimer = WindowRate;  // host pushes one timestamp per rendered frame
using TapCounter = WindowRate;   // host pushes one timestamp per tap

// "HH:MM" in UTC from epoch seconds. UTC (not device-local) is deliberate:
// timezone formatting is a UI-layer concern; this stays deterministic.
inline std::string formatClock(std::int64_t epochSeconds) {
    const std::int64_t day = ((epochSeconds % 86400) + 86400) % 86400;
    const int hh = static_cast<int>(day / 3600);
    const int mm = static_cast<int>((day % 3600) / 60);
    char buf[6];
    buf[0] = static_cast<char>('0' + hh / 10);
    buf[1] = static_cast<char>('0' + hh % 10);
    buf[2] = ':';
    buf[3] = static_cast<char>('0' + mm / 10);
    buf[4] = static_cast<char>('0' + mm % 10);
    buf[5] = '\0';
    return std::string(buf);
}

// Elapsed milliseconds -> "MM:SS" or "H:MM:SS" (session/stopwatch).
inline std::string formatSession(std::int64_t elapsedMs) {
    if (elapsedMs < 0) {
        elapsedMs = 0;
    }
    const std::int64_t s = elapsedMs / 1000;
    const std::int64_t h = s / 3600;
    const int mm = static_cast<int>((s % 3600) / 60);
    const int ss = static_cast<int>(s % 60);
    std::string out;
    if (h > 0) {
        out += std::to_string(h);
        out += ':';
    }
    out += static_cast<char>('0' + mm / 10);
    out += static_cast<char>('0' + mm % 10);
    out += ':';
    out += static_cast<char>('0' + ss / 10);
    out += static_cast<char>('0' + ss % 10);
    return out;
}

inline std::string fpsText(FrameTimer& frames, std::int64_t nowMs) {
    // A rate needs at least 2 samples; with fewer, no claim is made.
    if (frames.count(nowMs) < 2) {
        return kUnavailable;
    }
    return std::to_string(frames.count(nowMs));
}

inline std::string cpsText(TapCounter& taps, std::int64_t nowMs) {
    // Zero measured taps in the window is a verified zero, not unknown.
    return std::to_string(taps.count(nowMs));
}

// Installs providers for elements with verified local sources.
// Signature mirrors RenderContext hosts: the host owns the sources and
// passes a nowMs clock for deterministic tests.
inline void bindLocalProviders(HudLayout& layout, FrameTimer& frames, TapCounter& taps,
                               std::function<std::int64_t()> nowMs) {
    for (auto& el : layout.elements) {
        if (el.type == ElementType::Fps) {
            el.provider = [&frames, nowMs]() { return fpsText(frames, nowMs()); };
        } else if (el.type == ElementType::Cps) {
            el.provider = [&taps, nowMs]() { return cpsText(taps, nowMs()); };
        }
    }
}

// ---------------------------------------------------------------------------
// Hardware stats (hud.hardware_stats)
//
// Device/app facts the HUD can show: CPU cores, heap, system memory, storage,
// ABI. Every field is optional because the reader may genuinely not know one;
// a missing field is omitted rather than rendered as zero.
//
// GPU *utilisation* is deliberately not representable. No public Android API
// exposes it, so the struct has no field for it and no code path can print one.
// Capability data (driver name, API level) is legitimate information and is
// carried as strings by the host if it has read them.
struct HardwareStats {
    int cpuCores = 0;                 // 0 = unknown
    long long javaHeapUsedMB = -1;    // <0 = unknown
    long long systemAvailableMB = -1; // <0 = unknown
    long long systemTotalMB = -1;     // <0 = unknown
    long long storageFreeMB = -1;     // <0 = unknown
    bool systemLowMemory = false;
    std::string abi;                  // empty = unknown

    bool anyKnown() const {
        return cpuCores > 0 || javaHeapUsedMB >= 0 || systemAvailableMB >= 0 ||
               systemTotalMB >= 0 || storageFreeMB >= 0 || !abi.empty();
    }
};

// Appends "label value" when value is known. Deterministic order: core/heap/
// memory/storage/ABI. Bounded to kHardwareStatsMaxFields entries.
inline constexpr int kHardwareStatsMaxFields = 5;
inline constexpr std::size_t kHardwareStatsMaxText = 96;

// "arm64-v8a" -> "arm64"; anything else is passed through verbatim.
inline std::string shortAbi(const std::string& abi) {
    if (abi.size() > 4 && abi.compare(abi.size() - 4, 4, "-v8a") == 0) {
        return abi.substr(0, abi.size() - 4);
    }
    return abi;
}

// Deterministic, bounded, never fabricated. Returns kUnavailable when the
// struct carries nothing known, so a host that never measured shows "--"
// instead of an empty or zeroed line.
inline std::string formatHardwareStats(const HardwareStats& s) {
    if (!s.anyKnown()) {
        return kUnavailable;
    }
    std::string out;
    int fields = 0;
    auto add = [&](const char* label, const std::string& value) {
        if (fields >= kHardwareStatsMaxFields || out.size() >= kHardwareStatsMaxText) {
            return;
        }
        if (!out.empty()) {
            out += "  ";
        }
        out += label;
        out += " ";
        out += value;
        ++fields;
    };
    if (s.cpuCores > 0) {
        add("CPU", std::to_string(s.cpuCores) + "c");
    }
    if (s.javaHeapUsedMB >= 0) {
        add("Heap", std::to_string(s.javaHeapUsedMB) + "MB");
    }
    if (s.systemAvailableMB >= 0 || s.systemTotalMB >= 0) {
        std::string v;
        if (s.systemAvailableMB >= 0) {
            v += std::to_string(s.systemAvailableMB) + "/" +
                 std::to_string(s.systemTotalMB >= 0 ? s.systemTotalMB : 0) + "MB";
        } else {
            v = "/" + std::to_string(s.systemTotalMB) + "MB";
        }
        add(s.systemLowMemory ? "MEM!" : "MEM", v);
    }
    if (s.storageFreeMB >= 0) {
        add("Store", std::to_string(s.storageFreeMB) + "MB");
    }
    if (!s.abi.empty()) {
        add("ABI", shortAbi(s.abi));
    }
    if (out.size() > kHardwareStatsMaxText) {
        out.resize(kHardwareStatsMaxText);
    }
    return out.empty() ? kUnavailable : out;
}

// Installs the hardware_stats provider on every HardwareStats element.
// A default-constructed source installs an empty provider, so an unmeasured
// element renders kUnavailable rather than a fabricated line.
inline void bindHardwareStatsProvider(HudLayout& layout, const HardwareStats& stats) {
    for (auto& el : layout.elements) {
        if (el.type == ElementType::HardwareStats) {
            el.provider = [stats]() { return formatHardwareStats(stats); };
        }
    }
}

// ---------------------------------------------------------------------------
// Motion HUD (hud.coordinates, hud.direction, hud.speed_meter) — Stage 12
// observation feed. Pure formatting over already-validated observation
// objects; no clock, no platform, no fabrication.

// Minecraft yaw convention: 0 = south (+Z), positive turns westward.
// 8-point compass, half-step hysteresis (22.5° bucket edges).
// NaN/out-of-range yaw degrades to kUnavailable, never a random heading.
inline std::string formatDirection(double yawDegrees) {
    if (!std::isfinite(yawDegrees)) {
        return kUnavailable;
    }
    double a = std::fmod(yawDegrees, 360.0);
    if (a < 0.0) {
        a += 360.0;
    }
    static const char* kPoints[8] = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    const int idx = static_cast<int>((a + 22.5) / 45.0) % 8;
    return kPoints[idx];
}

// "x y z" at one decimal from an observed travel position.
inline std::string formatCoordinates(const xykell::runtime::PlayerTravelObservation& t) {
    char buf[96];
    std::snprintf(buf, sizeof(buf), "%.1f %.1f %.1f", t.position.x, t.position.y, t.position.z);
    return std::string(buf);
}

// "N.N m/s" from the consumer-derived delta; absent/invalid -> kUnavailable.
inline std::string formatSpeed(const std::optional<double>& mps) {
    if (!mps.has_value() || !std::isfinite(*mps) || *mps < 0.0) {
        return kUnavailable;
    }
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%.1f m/s", *mps);
    return std::string(buf);
}

// Installs providers for the motion elements from an observation snapshot.
// The snapshot POINTER is captured: callers must keep it alive for the
// layout's lifetime (refreshHud passes the process-wide consumer's snapshot,
// a stable static). Null or empty snapshot renders kUnavailable via text().
inline void bindMotionProviders(HudLayout& layout,
                                const xykell::runtime::RuntimeObservationSnapshot* snap) {
    for (auto& el : layout.elements) {
        switch (el.type) {
            case ElementType::Coordinates:
                el.provider = [snap]() {
                    if (snap == nullptr || !snap->latestTravel.has_value()) {
                        return std::string(kUnavailable);
                    }
                    return formatCoordinates(*snap->latestTravel);
                };
                break;
            case ElementType::Direction:
                el.provider = [snap]() {
                    if (snap == nullptr || !snap->latestTravel.has_value()) {
                        return std::string(kUnavailable);
                    }
                    return formatDirection(snap->latestTravel->yawDegrees);
                };
                break;
            case ElementType::SpeedMeter:
                el.provider = [snap]() {
                    if (snap == nullptr) {
                        return std::string(kUnavailable);
                    }
                    return formatSpeed(snap->speedMps);
                };
                break;
            default:
                break;
        }
    }
}

// ---------------------------------------------------------------------------
// Vitals HUD (hud.health, hud.low_health) — observed SetHealth 0x2A.
//
// The wire value is Bedrock's half-heart unit (a full bar arrives as 20), and
// that is what gets rendered: the server never states a maximum, so inventing
// "20/20" would print a denominator nobody reported. Absent health renders
// kUnavailable, which is the honest state before the first SetHealth.

// "20" from the observed value; absent -> kUnavailable.
inline std::string formatHealth(const std::optional<int>& health) {
    if (!health.has_value()) {
        return kUnavailable;
    }
    return std::to_string(*health);
}

// "LOW" only while the observed value is at or under the threshold, and
// kUnavailable when health has never been observed: an unknown value must not
// read as a low-health alarm.
inline std::string formatLowHealth(const std::optional<int>& health, int threshold) {
    if (!health.has_value()) {
        return kUnavailable;
    }
    return (*health <= threshold) ? "LOW" : std::string(kUnavailable);
}

// Installs the vitals providers from an observation snapshot. The snapshot
// POINTER is captured (same lifetime rule as bindMotionProviders).
inline void bindVitalsProviders(HudLayout& layout,
                                const xykell::runtime::RuntimeObservationSnapshot* snap,
                                int lowHealthThreshold = 6) {
    for (auto& el : layout.elements) {
        switch (el.type) {
            case ElementType::Health:
                el.provider = [snap]() {
                    return snap == nullptr ? std::string(kUnavailable)
                                           : formatHealth(snap->latestHealth);
                };
                break;
            case ElementType::LowHealth:
                el.provider = [snap, lowHealthThreshold]() {
                    return snap == nullptr
                               ? std::string(kUnavailable)
                               : formatLowHealth(snap->latestHealth, lowHealthThreshold);
                };
                break;
            default:
                break;
        }
    }
}

// ---------------------------------------------------------------------------
// Population HUD (hud.entity_counter) + clock rate (hud.tps).
//
// Both are observed counts/rates, never world truth: the entity number is what
// the relay has been told about, and the rate is the server's own tick delta.

// "3 (1p)" from observed counts; absent -> kUnavailable. The player subset is
// omitted rather than guessed when it was never reported.
inline std::string formatEntityCount(const std::optional<std::uint64_t>& entities,
                                     const std::optional<std::uint64_t>& players) {
    if (!entities.has_value()) {
        return kUnavailable;
    }
    std::string out = std::to_string(*entities);
    if (players.has_value()) {
        out += " (" + std::to_string(*players) + "p)";
    }
    return out;
}

// "19.9 tps" from the derived tick rate; absent -> kUnavailable. A rate needs
// two clock samples, so the first SetTime honestly renders as unknown rather
// than as 0.
inline std::string formatTps(const std::optional<double>& tps) {
    if (!tps.has_value() || !std::isfinite(*tps) || *tps < 0.0) {
        return kUnavailable;
    }
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%.1f tps", *tps);
    return std::string(buf);
}

// The online roster as PlayerList 0x3f reported it, one name per line prefix.
//
// An empty roster and a roster that was never reported are different claims, so
// the caller distinguishes them: `reported` is false until the server has sent
// a single entry, and that case renders kUnavailable rather than "0 players".
// The count is a count of what the server told us, never of who is really in
// the world -- a player who joined while the relay was down is simply absent.
inline std::string formatTabList(const std::vector<std::string>& roster, bool reported,
                                 std::size_t limit) {
    if (!reported || roster.empty()) {
        return kUnavailable;
    }
    // The label lives here rather than in the renderer: the TabList case prints
    // the provider's line verbatim, so a renderer-side prefix would double it.
    std::string out = std::to_string(roster.size()) + " online: ";
    std::size_t shown = 0;
    for (const auto& name : roster) {
        if (shown >= limit) break;
        if (shown > 0) out += ", ";
        out += name;
        ++shown;
    }
    if (roster.size() > limit) {
        out += ", +" + std::to_string(roster.size() - limit);
    }
    return out;
}

// How many names a tab list line prints before it says how many it left out.
inline constexpr std::size_t kTabListNames = 6;

// The upstream host and the protocol version the client's own login packet
// announced. Both absent -> kUnavailable: a server_info line rendered before
// the session connected would be a confident-looking claim about nothing.
inline std::string formatServerInfo(const std::optional<runtime::SessionEndpointObservation>& conn) {
    if (!conn.has_value() || conn->host.empty() || conn->protocolVersion <= 0) {
        return kUnavailable;
    }
    return conn->host + " (proto " + std::to_string(conn->protocolVersion) + ")";
}

// host:port only. This is the address the relay is connected to, which is what
// the user configured -- not a claim about anything the server reports.
inline std::string formatIpDisplay(const std::optional<runtime::SessionEndpointObservation>& conn) {
    if (!conn.has_value() || conn->host.empty() || conn->port == 0) {
        return kUnavailable;
    }
    return conn->host + ":" + std::to_string(conn->port);
}

// Installs the population + clock-rate providers from an observation snapshot.
// The snapshot POINTER is captured (same lifetime rule as bindMotionProviders).
inline void bindPopulationProviders(
    HudLayout& layout, const xykell::runtime::RuntimeObservationSnapshot* snap) {
    for (auto& el : layout.elements) {
        switch (el.type) {
            case ElementType::EntityCounter:
                el.provider = [snap]() {
                    if (snap == nullptr) {
                        return std::string(kUnavailable);
                    }
                    return formatEntityCount(snap->latestEntityCount, snap->latestPlayerCount);
                };
                break;
            case ElementType::Tps:
                el.provider = [snap]() {
                    if (snap == nullptr) {
                        return std::string(kUnavailable);
                    }
                    return formatTps(snap->ticksPerSecond);
                };
                break;
            case ElementType::ServerInfo:
                el.provider = [snap]() {
                    if (snap == nullptr) return std::string(kUnavailable);
                    return formatServerInfo(snap->latestConnection);
                };
                break;
            case ElementType::IpDisplay:
                el.provider = [snap]() {
                    if (snap == nullptr) return std::string(kUnavailable);
                    return formatIpDisplay(snap->latestConnection);
                };
                break;
            case ElementType::TabList:
                el.provider = [snap]() {
                    if (snap == nullptr || snap->rosterCount == 0) {
                        return std::string(kUnavailable);
                    }
                    return formatTabList(snap->playerRoster, true, kTabListNames);
                };
                break;
            default:
                break;
        }
    }
}

// First real modules: toggleable descriptors bound to the providers above.
// Returns the number actually registered (duplicates skipped safely).
inline int registerLocalModules(ModuleManager& mods) {
    static const char* kIds[][3] = {
        {"xykell.hud.fps", "FPS Display", "hud"},
        {"xykell.hud.cps", "CPS Display", "hud"},
        {"xykell.hud.clock", "Clock", "hud"},
        {"xykell.hud.session_stats", "Session Stats", "hud"},
        {"xykell.hud.stop_watch", "Stopwatch", "hud"},
    };
    int added = 0;
    for (const auto& m : kIds) {
        if (mods.registerModule(ModuleDescriptor(m[0], m[1], m[2]))) {
            ++added;
        }
    }
    return added;
}

} // namespace xykell::hud::sources
