// Host unit test: app->game observation feed client (pure parse + loopback
// socket loop). Synthetic lines only; loop test binds 127.0.0.1 ephemeral.
// Wire vocabulary mirrors Translated (ObservationTranslator):
// travel/chat/vitals.
#include <arpa/inet.h>
#include <cassert>
#include <atomic>
#include <chrono>
#include <cstring>
#include <iostream>
#include <netinet/in.h>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

#include "xykell/feed_client.h"
#include "xykell/runtime_observation_consumer.h"

using xykell::feed::applyLine;
using xykell::feed::runLoop;
using xykell::runtime::ObservationConsumer;

namespace {

std::string travelLine(const char* id, std::uint64_t at, double x, double y,
                       double z, double yaw, double m, int method) {
    return std::string("{\"t\":\"travel\",\"id\":\"") + id +
           "\",\"at\":" + std::to_string(at) +
           ",\"x\":" + std::to_string(x) +
           ",\"y\":" + std::to_string(y) +
           ",\"z\":" + std::to_string(z) +
           ",\"yaw\":" + std::to_string(yaw) +
           ",\"m\":" + std::to_string(m) +
           ",\"method\":" + std::to_string(method) + "}";
}

} // namespace

int main() {
    // --- applyLine: valid travel feeds the consumer (snapshot + speed) ---
    {
        ObservationConsumer c;
        assert(applyLine(c, travelLine("f1", 1000, 1.0, 64.0, 2.0, 90.0, 0.0, 0)));
        assert(c.snapshot().travelCount == 1);
        assert(c.snapshot().latestTravel.has_value());
        assert(c.snapshot().latestTravel->position.x == 1.0);
        assert(c.snapshot().latestTravel->yawDegrees == 90.0);
        assert(!c.snapshot().speedMps.has_value()); // needs two samples

        assert(applyLine(c, travelLine("f2", 2000, 4.0, 64.0, 6.0, 180.0, 5.0, 2)));
        assert(c.snapshot().travelCount == 2);
        // dist((1,64,2)->(4,64,6)) = 5 m over 1 s -> 5 m/s
        assert(c.snapshot().speedMps.has_value());
        assert(c.snapshot().speedMps.value() == 5.0);
        assert(c.snapshot().latestTravel->position.z == 6.0);
        assert(c.snapshot().latestTravel->metersTravelled == 5.0);
        assert(c.snapshot().latestTravel->travelMethod == 2);
    }

    // --- applyLine: valid chat feeds latest message ---
    {
        ObservationConsumer c;
        const std::string line =
            "{\"t\":\"chat\",\"id\":\"c1\",\"at\":55,"
            "\"sender\":\"Steve\",\"msg\":\"hello\"}";
        assert(applyLine(c, line));
        assert(c.snapshot().messageCount == 1);
        assert(c.snapshot().latestMessage.has_value());
        assert(c.snapshot().latestMessage->sender == "Steve");
        assert(c.snapshot().latestMessage->message == "hello");
        assert(c.snapshot().latestMessage->eventId == "c1");
    }

    // --- applyLine: vitals, where an absent key must mean "not observed" ---
    {
        ObservationConsumer c;
        // Health only. No "ticks" key at all: the clock stays unknown rather
        // than being read as a real zero.
        assert(applyLine(c, "{\"t\":\"vitals\",\"id\":\"v1\",\"at\":9,\"health\":18}"));
        assert(c.snapshot().vitalsCount == 1);
        assert(c.snapshot().latestHealth.value() == 18);
        assert(!c.snapshot().latestTimeTicks.has_value());
        // Clock only must NOT clear the health already stored.
        assert(applyLine(c, "{\"t\":\"vitals\",\"id\":\"v2\",\"at\":10,\"ticks\":6000}"));
        assert(c.snapshot().latestHealth.value() == 18);
        assert(c.snapshot().latestTimeTicks.value() == 6000);
        // A written 0 is a real zero, distinct from the absent case above.
        assert(applyLine(c, "{\"t\":\"vitals\",\"id\":\"v3\",\"at\":11,\"health\":0}"));
        assert(c.snapshot().latestHealth.value() == 0);
        // A negative value is rejected by the factory rather than stored.
        assert(!applyLine(c, "{\"t\":\"vitals\",\"id\":\"v4\",\"at\":12,\"health\":-1}"));
        // Neither field present is an empty line: rejected, snapshot untouched.
        assert(!applyLine(c, "{\"t\":\"vitals\",\"id\":\"v5\",\"at\":13}"));
        // A missing id is malformed.
        assert(!applyLine(c, "{\"t\":\"vitals\",\"at\":14,\"health\":20}"));
        assert(c.snapshot().latestHealth.value() == 0); // still the last valid one
        // Vitals never fabricate message or travel state.
        assert(!c.snapshot().latestMessage.has_value());
        assert(!c.snapshot().latestTravel.has_value());
    }

    // --- applyLine: malformed lines rejected, state untouched ---
    {
        ObservationConsumer c;
        assert(!applyLine(c, ""));
        assert(!applyLine(c, "not json"));
        assert(!applyLine(c, "{}")); // no t
        assert(!applyLine(c, "{\"t\":\"unknown\"}"));
        // missing required travel field
        assert(!applyLine(c,
                          "{\"t\":\"travel\",\"id\":\"x\",\"at\":1,"
                          "\"x\":0,\"y\":0,\"z\":0,\"yaw\":0}"));
        // non-finite coordinate
        assert(!applyLine(c,
                          "{\"t\":\"travel\",\"id\":\"x\",\"at\":1,"
                          "\"x\":1e999,\"y\":0,\"z\":0,\"yaw\":0,"
                          "\"m\":0,\"method\":0}"));
        // empty chat sender
        assert(!applyLine(c,
                          "{\"t\":\"chat\",\"id\":\"x\",\"at\":1,"
                          "\"sender\":\"\",\"msg\":\"hi\"}"));
        assert(c.totalConsumed() == 0);
    }

    // --- runLoop over a real loopback server: framing, garbage, stop ---
    {
        const int srv = ::socket(AF_INET, SOCK_STREAM, 0);
        assert(srv >= 0);
        int one = 1;
        ::setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        addr.sin_port = 0;
        assert(::bind(srv, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
        socklen_t alen = sizeof(addr);
        assert(::getsockname(srv, reinterpret_cast<sockaddr*>(&addr), &alen) == 0);
        const int port = ntohs(addr.sin_port);
        assert(::listen(srv, 1) == 0);

        ObservationConsumer c;
        std::atomic<bool> stop{false};
        std::thread client([&] { runLoop(c, "127.0.0.1", port, stop); });

        const int conn = ::accept(srv, nullptr, nullptr);
        assert(conn >= 0);
        // Garbage line, then split travel across two writes, then chat.
        const std::string good =
            travelLine("s1", 1000, 0.0, 60.0, 0.0, 0.0, 0.0, 0) + "\n" +
            travelLine("s2", 2000, 3.0, 60.0, 4.0, 0.0, 0.0, 0) + "\n" +
            "{\"t\":\"chat\",\"id\":\"cc\",\"at\":7,"
            "\"sender\":\"Alex\",\"msg\":\"yo\"}\n";
        const std::string bad = "garbage\n{}\n";
        assert(::send(conn, bad.data(), bad.size(), 0) ==
               static_cast<ssize_t>(bad.size()));
        const std::string head = good.substr(0, 20);
        const std::string tail = good.substr(20);
        assert(::send(conn, head.data(), head.size(), 0) ==
               static_cast<ssize_t>(head.size()));
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        assert(::send(conn, tail.data(), tail.size(), 0) ==
               static_cast<ssize_t>(tail.size()));

        for (int i = 0; i < 200 && c.snapshot().messageCount == 0; ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        assert(c.snapshot().travelCount == 2);
        assert(c.snapshot().messageCount == 1);
        assert(c.snapshot().speedMps.has_value()); // two samples seen

        stop = true;
        ::close(conn);
        ::shutdown(srv, SHUT_RDWR);
        ::close(srv);
        client.join();
    }

    // --- runLoop: server down first, client retries and connects ---
    {
        ObservationConsumer c;
        std::atomic<bool> stop{false};
        // Bind then close to learn a free port that is initially closed.
        const int probe = ::socket(AF_INET, SOCK_STREAM, 0);
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        ::bind(probe, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
        socklen_t alen = sizeof(addr);
        ::getsockname(probe, reinterpret_cast<sockaddr*>(&addr), &alen);
        ::close(probe);
        const int port = ntohs(addr.sin_port);

        std::thread client([&] { runLoop(c, "127.0.0.1", port, stop); });
        std::this_thread::sleep_for(std::chrono::milliseconds(300));

        const int srv = ::socket(AF_INET, SOCK_STREAM, 0);
        int one = 1;
        ::setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        assert(::bind(srv, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
        assert(::listen(srv, 1) == 0);
        const int conn = ::accept(srv, nullptr, nullptr);
        assert(conn >= 0);
        const std::string line =
            travelLine("late", 500, 9.0, 1.0, 9.0, 270.0, 0.0, 0) + "\n";
        assert(::send(conn, line.data(), line.size(), 0) ==
               static_cast<ssize_t>(line.size()));
        for (int i = 0; i < 300 && c.snapshot().travelCount == 0; ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        assert(c.snapshot().travelCount == 1); // reconnect worked
        stop = true;
        ::close(conn);
        ::close(srv);
        client.join();
    }

    std::cout << "test_feed_client: PASS\n";
    return 0;
}
