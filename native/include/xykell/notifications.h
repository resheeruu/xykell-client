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
    std::string text;
    NotifyPriority priority = NotifyPriority::Info;
    int durationMs = 3000;
};

class NotificationCenter {
  public:
    static constexpr std::size_t kCap = 32;

    void post(const std::string& text,
              NotifyPriority priority = NotifyPriority::Info,
              int durationMs = 3000);
    std::vector<Notification> drain(); // returns + clears
    std::size_t pending() const { return queue_.size(); }
    void clear();

  private:
    std::vector<Notification> queue_;
    std::uint64_t next_ = 1;
};

} // namespace xykell::ui
