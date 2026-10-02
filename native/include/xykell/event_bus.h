#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

// Tiny typed event bus. Pure C++ (no Android/preloader/game headers).
// A throwing subscriber never breaks dispatch: the failure is counted and
// dispatch continues. Ownership is by module id for bulk unsubscribe.
namespace xykell {

enum class EventType : std::uint8_t {
    Tick = 0,
    Render,
    Input,
    WorldChange,
    Packet,
    Config,
    Shutdown,
};

struct Event {
    EventType type = EventType::Tick;
    std::uint64_t sequence = 0;
};

using EventHandler = std::function<void(const Event&)>;

class EventBus {
  public:
    using Token = std::uint64_t;

    Token subscribe(EventType type, const std::string& moduleId, EventHandler fn);
    void unsubscribe(Token token);
    void unsubscribeModule(const std::string& moduleId);

    struct DispatchResult {
        std::size_t delivered = 0;
        std::size_t failed = 0;
    };
    DispatchResult publish(const Event& event);

    std::size_t subscriberCount(EventType type) const;
    std::size_t failuresFor(const std::string& moduleId) const;

  private:
    struct Subscription {
        Token token;
        std::string moduleId;
        EventHandler fn;
    };
    std::unordered_map<EventType, std::vector<Subscription>> subs_;
    std::unordered_map<std::string, std::size_t> failures_;
    Token nextToken_ = 1;
};

} // namespace xykell
