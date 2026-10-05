#include "xykell/notifications.h"

namespace xykell::ui {

void NotificationCenter::post(const std::string& text, NotifyPriority priority,
                              int durationMs, std::uint64_t atMs) {
    if (text.empty()) {
        return;
    }
    if (queue_.size() >= kCap) {
        queue_.erase(queue_.begin()); // drop oldest, stay bounded
    }
    Notification n;
    n.seq = next_++;
    n.postedAtMs = atMs;
    n.text = text;
    n.priority = priority;
    n.durationMs = durationMs;
    queue_.push_back(std::move(n));
}

std::vector<Notification> NotificationCenter::drain() {
    std::vector<Notification> out = std::move(queue_);
    queue_.clear();
    return out;
}

void NotificationCenter::clear() { queue_.clear(); }

std::size_t NotificationCenter::expireOlderThan(std::uint64_t nowMs,
                                                std::uint64_t maxAgeMs) {
    if (queue_.empty() || maxAgeMs == 0) {
        return 0;
    }
    // Oldest first, so a single pop_back is not enough.
    std::size_t removed = 0;
    while (!queue_.empty() && nowMs > queue_.front().postedAtMs &&
           (nowMs - queue_.front().postedAtMs) > maxAgeMs) {
        queue_.erase(queue_.begin());
        ++removed;
    }
    return removed;
}

} // namespace xykell::ui
