#include "xykell/event_bus.h"

namespace xykell {

EventBus::Token EventBus::subscribe(EventType type, const std::string& moduleId,
                                     EventHandler fn) {
    const Token t = nextToken_++;
    subs_[type].push_back(Subscription{t, moduleId, std::move(fn)});
    return t;
}

void EventBus::unsubscribe(Token token) {
    for (auto& [type, vec] : subs_) {
        for (auto it = vec.begin(); it != vec.end(); ++it) {
            if (it->token == token) {
                vec.erase(it);
                return;
            }
        }
    }
}

void EventBus::unsubscribeModule(const std::string& moduleId) {
    for (auto& [type, vec] : subs_) {
        for (auto it = vec.begin(); it != vec.end();) {
            if (it->moduleId == moduleId) {
                it = vec.erase(it);
            } else {
                ++it;
            }
        }
    }
}

EventBus::DispatchResult EventBus::publish(const Event& event) {
    DispatchResult r;
    auto it = subs_.find(event.type);
    if (it == subs_.end()) {
        return r;
    }
    // Snapshot: subscribers may (un)subscribe during dispatch.
    const auto snapshot = it->second;
    for (const auto& sub : snapshot) {
        try {
            sub.fn(event);
            ++r.delivered;
        } catch (...) {
            ++r.failed;
            ++failures_[sub.moduleId];
        }
    }
    return r;
}

std::size_t EventBus::subscriberCount(EventType type) const {
    const auto it = subs_.find(type);
    return it == subs_.end() ? 0 : it->second.size();
}

std::size_t EventBus::failuresFor(const std::string& moduleId) const {
    const auto it = failures_.find(moduleId);
    return it == failures_.end() ? 0 : it->second;
}

} // namespace xykell
