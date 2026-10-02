// Host unit test: EventBus isolation. A throwing subscriber must not
// break dispatch to others.
#include <cassert>
#include <iostream>
#include <stdexcept>

#include "xykell/event_bus.h"

int main() {
    xykell::EventBus bus;
    int good = 0;
    bus.subscribe(xykell::EventType::Tick, "good", [&](const xykell::Event&) { ++good; });
    bus.subscribe(xykell::EventType::Tick, "bad", [](const xykell::Event&) {
        throw std::runtime_error("boom");
    });
    assert(bus.subscriberCount(xykell::EventType::Tick) == 2);

    const auto res = bus.publish(xykell::Event{xykell::EventType::Tick, 1});
    assert(res.delivered == 1 && res.failed == 1);
    assert(good == 1);
    assert(bus.failuresFor("bad") == 1);
    assert(bus.failuresFor("good") == 0);

    // Unsubscribe one token: only "bad" remains.
    xykell::EventBus bus2;
    int a = 0, b = 0;
    const auto t = bus2.subscribe(xykell::EventType::Input, "a",
                                  [&](const xykell::Event&) { ++a; });
    bus2.subscribe(xykell::EventType::Input, "b", [&](const xykell::Event&) { ++b; });
    bus2.unsubscribe(t);
    bus2.publish(xykell::Event{xykell::EventType::Input, 2});
    assert(a == 0 && b == 1);

    // Module-wide unsubscribe.
    bus2.unsubscribeModule("b");
    assert(bus2.subscriberCount(xykell::EventType::Input) == 0);
    const auto empty = bus2.publish(xykell::Event{xykell::EventType::Input, 3});
    assert(empty.delivered == 0 && empty.failed == 0);

    std::cout << "test_bus: PASS\n";
    return 0;
}
