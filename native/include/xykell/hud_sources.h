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
#include <functional>
#include <string>
#include <vector>

#include "xykell/hud_model.h"
#include "xykell/module_manager.h"

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
