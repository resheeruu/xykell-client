#include "xykell/notifications.h"

namespace xykell::ui {

void NotificationCenter::post(const std::string& text, NotifyPriority priority,
                              int durationMs) {
    if (text.empty()) {
        return;
    }
    if (queue_.size() >= kCap) {
        queue_.erase(queue_.begin()); // drop oldest, stay bounded
    }
    queue_.push_back(Notification{next_++, text, priority, durationMs});
}

std::vector<Notification> NotificationCenter::drain() {
    std::vector<Notification> out = std::move(queue_);
    queue_.clear();
    return out;
}

void NotificationCenter::clear() { queue_.clear(); }

} // namespace xykell::ui
