#pragma once

// Notification center: bounded in-memory queue (module/runtime/diagnostic
// toasts). No sound/vibration backends here — the renderer/presenter decides
// presentation. Pure + tested; overflow drops oldest, never grows unbounded.
#include <cstdint>
#include <string>
#include <vector>

namespace xykell::ui {

enum class NotifyPriority { Info = 0, Warning = 1, Error = 2 };

struct Notification {
    std::uint64_t seq = 0;
    std::uint64_t postedAtMs = 0;
    std::string text;
    NotifyPriority priority = NotifyPriority::Info;
    int durationMs = 3000;
};

class NotificationCenter {
  public:
    static constexpr std::size_t kCap = 32;

    // atMs is supplied by the caller (the Android layer passes
    // SystemClock.uptimeMillis()). The center stays pure so expiry is
    // testable without a clock.
    void post(const std::string& text,
              NotifyPriority priority = NotifyPriority::Info,
              int durationMs = 3000,
              std::uint64_t atMs = 0);
    std::vector<Notification> drain(); // returns + clears
    std::size_t pending() const { return queue_.size(); }
    void clear();

    // Read without consuming. The HUD renders every frame, so it must not
    // drain: a hidden or off-screen HUD would otherwise eat notifications.
    const std::vector<Notification>& peek() const { return queue_; }

    // Drop entries older than maxAgeMs relative to now, oldest first. Keeps a
    // queue that nothing drains from showing stale text forever.
    std::size_t expireOlderThan(std::uint64_t nowMs, std::uint64_t maxAgeMs);

  private:
    std::vector<Notification> queue_;
    std::uint64_t next_ = 1;
};

} // namespace xykell::ui
