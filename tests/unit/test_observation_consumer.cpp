// Host unit test: read-only observation consumer (Stage 12).
// Synthetic Stage-10-schema fixtures only; never Minecraft data.
// Pure C++, deterministic, no I/O.
#include <cassert>
#include <iostream>
#include <optional>
#include <string>

#include "xykell/runtime_observation_consumer.h"

using namespace xykell::runtime;

namespace {

PlayerMessageObservation msg(const std::string& id, std::uint64_t at, const std::string& text) {
    auto o = makePlayerMessage(id, at, "TestPlayer", text);
    assert(o.has_value());
    return *o;
}

PlayerTravelObservation travel(const std::string& id, std::uint64_t at, double x, int method) {
    auto o = makePlayerTravel(id, at, Vec3{x, 64.0, -370.0}, -7.0, 1.05, method);
    assert(o.has_value());
    return *o;
}

UnknownObservation unknown(const std::string& id, std::uint64_t at, std::uint64_t wire) {
    auto o = makeUnknown(id, at, wire, "non-json-plaintext");
    assert(o.has_value());
    return *o;
}

VitalsObservation vitals(const std::string& id, std::uint64_t at, std::optional<int> health,
                         std::optional<int> ticks) {
    auto o = makeVitals(id, at, health, ticks);
    assert(o.has_value());
    return *o;
}

PlayerListObservation playerList(const std::string& id, std::uint64_t at, bool present,
                                 const std::string& uuid, const std::string& name) {
    auto o = makePlayerList(id, at, present, uuid, name);
    assert(o.has_value());
    return *o;
}

EntityPopulationObservation population(const std::string& id, std::uint64_t at,
                                       std::uint64_t entities, std::uint64_t players) {
    auto o = makeEntityPopulation(id, at, entities, players);
    assert(o.has_value());
    return *o;
}

} // namespace

int main() {
    // --- initial state: deterministic, nothing fabricated ---
    {
        ObservationConsumer c;
        const auto& s = c.snapshot();
        assert(!s.latestMessage.has_value());  // no PlayerMessage
        assert(!s.latestTravel.has_value());   // no PlayerTravelled
        assert(s.messageCount == 0 && s.travelCount == 0 && s.unknownCount == 0);
        assert(c.totalConsumed() == 0);
        assert(s.lastObservedAtMs == 0);
    }
    // --- PlayerMessage: stored, fields + timestamp preserved ---
    {
        ObservationConsumer c;
        c.consume(RuntimeObservation{msg("xykell-obs-1", 100ULL, "stage12")});
        const auto& s = c.snapshot();
        assert(s.latestMessage.has_value());
        assert(s.latestMessage->sender == "TestPlayer");
        assert(s.latestMessage->message == "stage12");
        assert(s.latestMessage->observedAtMs == 100ULL);
        assert(s.messageCount == 1 && c.totalConsumed() == 1);
        // latest replaces previous
        c.consume(RuntimeObservation{msg("xykell-obs-2", 200ULL, "second")});
        assert(c.snapshot().latestMessage->message == "second");
        assert(c.snapshot().messageCount == 2);
        assert(c.snapshot().lastObservedAtMs == 200ULL);
    }
    // --- PlayerTravelled: full telemetry preserved ---
    {
        ObservationConsumer c;
        c.consume(RuntimeObservation{travel("xykell-obs-3", 300ULL, -485.5, 2)});
        const auto& s = c.snapshot();
        assert(s.latestTravel.has_value());
        assert(s.latestTravel->position.x == -485.5);
        assert(s.latestTravel->position.y == 64.0 && s.latestTravel->position.z == -370.0);
        assert(s.latestTravel->yawDegrees == -7.0);
        assert(s.latestTravel->metersTravelled == 1.05);
        assert(s.latestTravel->travelMethod == 2);  // raw, unmapped
        assert(s.travelCount == 1);
    }
    // --- multiple events: latest snapshot stays correct per type ---
    {
        ObservationConsumer c;
        c.consume(RuntimeObservation{msg("m1", 1ULL, "one")});
        c.consume(RuntimeObservation{travel("t1", 2ULL, 1.0, 0)});
        c.consume(RuntimeObservation{msg("m2", 3ULL, "two")});
        c.consume(RuntimeObservation{travel("t2", 4ULL, 2.0, 2)});
        const auto& s = c.snapshot();
        assert(s.latestMessage->message == "two");
        assert(s.latestTravel->position.x == 2.0);
        assert(s.messageCount == 2 && s.travelCount == 2);
        assert(s.lastObservedAtMs == 4ULL);
        assert(c.totalConsumed() == 4);
    }
    // --- Unknown: counted, never promoted, payload never retained ---
    {
        ObservationConsumer c;
        c.consume(RuntimeObservation{unknown("u1", 50ULL, 404)});
        const auto& s = c.snapshot();
        assert(s.unknownCount == 1);
        assert(!s.latestMessage.has_value());  // no message created
        assert(!s.latestTravel.has_value());   // no travel created
        assert(s.lastUnknownWireLength == 404);
        assert(s.lastUnknownReason == "non-json-plaintext");
        assert(c.totalConsumed() == 1);
        c.consume(RuntimeObservation{unknown("u2", 60ULL, 115)});
        assert(c.snapshot().unknownCount == 2);
    }
    // --- immutability: caller-side mutation cannot alter stored state ---
    {
        ObservationConsumer c;
        PlayerMessageObservation m = msg("xykell-obs-9", 90ULL, "original");
        c.consume(RuntimeObservation{m});
        m.message = "forged";
        m.sender = "forged";
        assert(c.snapshot().latestMessage->message == "original");
        assert(c.snapshot().latestMessage->sender == "TestPlayer");
        // Snapshot is returned by const ref: no non-const access path.
        static_assert(std::is_same<decltype(c.snapshot()),
                                   const RuntimeObservationSnapshot&>::value,
                      "snapshot must be const-access only");
    }
    // --- speed: derived from consecutive travel positions, bounded ---
    {
        ObservationConsumer c;
        assert(!c.snapshot().speedMps.has_value()); // nothing observed yet
        c.consume(RuntimeObservation{travel("s1", 100ULL, 1.0, 0)});
        assert(!c.snapshot().speedMps.has_value()); // one sample: no delta
        c.consume(RuntimeObservation{travel("s2", 600ULL, 4.0, 0)});
        assert(c.snapshot().speedMps.has_value());
        // |4.0 - 1.0| = 3 m over 500 ms -> 6.0 m/s.
        assert(c.snapshot().speedMps.value() > 5.99 && c.snapshot().speedMps.value() < 6.01);
        // Equal timestamps: division by zero never happens, last speed kept.
        c.consume(RuntimeObservation{travel("s3", 600ULL, 10.0, 0)});
        assert(c.snapshot().speedMps.value() > 5.99 && c.snapshot().speedMps.value() < 6.01);
        // Backward timestamp: ignored, last valid speed kept.
        c.consume(RuntimeObservation{travel("s4", 100ULL, 50.0, 0)});
        assert(c.snapshot().speedMps.value() > 5.99 && c.snapshot().speedMps.value() < 6.01);
        // Zero movement over 1 s -> exactly 0 m/s (observed, not fabricated).
        ObservationConsumer still;
        still.consume(RuntimeObservation{travel("z1", 0ULL, 7.0, 0)});
        still.consume(RuntimeObservation{travel("z2", 1000ULL, 7.0, 0)});
        assert(still.snapshot().speedMps.has_value());
        assert(still.snapshot().speedMps.value() == 0.0);
    }
    // --- vitals: each field merges independently, never defaulted to zero ---
    {
        ObservationConsumer c;
        assert(!c.snapshot().latestHealth.has_value());
        assert(!c.snapshot().latestTimeTicks.has_value());
        // SetHealth 0x2A: health only.
        c.consume(RuntimeObservation{vitals("h1", 10ULL, 16, std::nullopt)});
        assert(c.snapshot().latestHealth.value() == 16);
        assert(!c.snapshot().latestTimeTicks.has_value());  // still unknown
        // SetTime 0x0A: clock only. It MUST NOT clear the health already seen.
        c.consume(RuntimeObservation{vitals("t1", 20ULL, std::nullopt, 4321)});
        assert(c.snapshot().latestHealth.value() == 16);
        assert(c.snapshot().latestTimeTicks.value() == 4321);
        assert(c.snapshot().vitalsCount == 2);
        // A later health value replaces it.
        c.consume(RuntimeObservation{vitals("h2", 30ULL, 4, std::nullopt)});
        assert(c.snapshot().latestHealth.value() == 4);
        assert(c.snapshot().latestTimeTicks.value() == 4321);
    }
    // --- vitals: an empty observation is rejected, never stored ---
    {
        assert(!makeVitals("", 1ULL, 20, std::nullopt).has_value());  // empty id
        assert(!makeVitals("v", 1ULL, std::nullopt, std::nullopt).has_value());  // neither
        assert(!makeVitals("v", 1ULL, -1, std::nullopt).has_value());  // negative health
        assert(!makeVitals("v", 1ULL, std::nullopt, -5).has_value());  // negative ticks
        // Zero is a legitimate observation (a real 0 health / tick 0).
        assert(makeVitals("v", 1ULL, 0, std::nullopt).has_value());
    }
    // --- vitals never manufacture message or travel state ---
    {
        ObservationConsumer c;
        c.consume(RuntimeObservation{vitals("v", 1ULL, 20, 99)});
        assert(!c.snapshot().latestMessage.has_value());
        assert(!c.snapshot().latestTravel.has_value());
        assert(c.snapshot().unknownCount == 0);
        assert(kindOf(RuntimeObservation{vitals("v", 1ULL, 20, 99)}) == ObservationKind::Vitals);
    }
    // --- population: observed counts, and an impossible pair rejected ---
    {
        ObservationConsumer c;
        assert(!c.snapshot().latestEntityCount.has_value());
        assert(!c.snapshot().latestPlayerCount.has_value());
        c.consume(RuntimeObservation{population("p1", 5ULL, 7, 2)});
        assert(c.snapshot().latestEntityCount.value() == 7);
        assert(c.snapshot().latestPlayerCount.value() == 2);
        assert(c.snapshot().populationCount == 1);
        // A later count replaces both; it is never merged field-by-field, so a
        // population with no players cannot leave a stale player count behind.
        c.consume(RuntimeObservation{population("p2", 6ULL, 1, 1)});
        assert(c.snapshot().latestEntityCount.value() == 1);
        assert(c.snapshot().latestPlayerCount.value() == 1);
        // players > entities is a caller bug, never stored.
        assert(!makeEntityPopulation("bad", 7ULL, 2, 5).has_value());
        assert(!makeEntityPopulation("", 7ULL, 2, 1).has_value());
        assert(makeEntityPopulation("ok", 7ULL, 0, 0).has_value());
        assert(kindOf(RuntimeObservation{population("p3", 8ULL, 0, 0)}) ==
               ObservationKind::EntityPopulation);
        // Population never manufactures message, travel or vitals state.
        assert(!c.snapshot().latestMessage.has_value());
        assert(!c.snapshot().latestTravel.has_value());
        assert(!c.snapshot().latestHealth.has_value());
    }
    // --- ticks per second: derived only from two distinct clock samples ---
    {
        ObservationConsumer c;
        assert(!c.snapshot().ticksPerSecond.has_value());
        // One SetTime is not a rate: no division by nothing.
        c.consume(RuntimeObservation{vitals("t1", 1000ULL, std::nullopt, 20)});
        assert(!c.snapshot().ticksPerSecond.has_value());
        // 40 ticks over 1 second.
        c.consume(RuntimeObservation{vitals("t2", 2000ULL, std::nullopt, 60)});
        assert(c.snapshot().ticksPerSecond.value() > 39.99 &&
               c.snapshot().ticksPerSecond.value() < 40.01);
        // A clock that does not advance keeps the last valid rate rather than
        // reporting a negative one.
        c.consume(RuntimeObservation{vitals("t3", 3000ULL, std::nullopt, 60)});
        assert(c.snapshot().ticksPerSecond.value() > 39.99 &&
               c.snapshot().ticksPerSecond.value() < 40.01);
        // Backward timestamps are ignored, not divided.
        c.consume(RuntimeObservation{vitals("t4", 500ULL, std::nullopt, 61)});
        assert(c.snapshot().ticksPerSecond.value() > 39.99 &&
               c.snapshot().ticksPerSecond.value() < 40.01);
        // A health-only observation must not disturb the rate.
        c.consume(RuntimeObservation{vitals("h9", 4000ULL, 12, std::nullopt)});
        assert(c.snapshot().ticksPerSecond.value() > 39.99 &&
               c.snapshot().ticksPerSecond.value() < 40.01);
    }
    // --- boundedness: fixed-size state regardless of volume ---
    {
        ObservationConsumer c;
        for (int i = 0; i < 1000; ++i) {
            c.consume(RuntimeObservation{msg("m", 1ULL, "x")});
            c.consume(RuntimeObservation{travel("t", 1ULL, 1.0, 0)});
            c.consume(RuntimeObservation{unknown("u", 1ULL, 9)});
            c.consume(RuntimeObservation{vitals("v", 1ULL, 20, std::nullopt)});
            c.consume(RuntimeObservation{population("p", 1ULL, 3, 1)});
        }
        const auto& s = c.snapshot();
        assert(s.messageCount == 1000 && s.travelCount == 1000 && s.unknownCount == 1000);
        assert(s.vitalsCount == 1000 && s.populationCount == 1000);
        assert(c.totalConsumed() == 5000);
        // Constant shape: optionals + scalars + one short string.
        assert(sizeof(RuntimeObservationSnapshot) < 512);
    }

    // --- the online roster: order, rename, removal, boundedness ---
    {
        ObservationConsumer c;
        const std::string u1(32, '1');
        const std::string u2(32, '2');
        c.consume(playerList("a", 1, true, u1, "Steve"));
        c.consume(playerList("b", 2, true, u2, "Alex"));
        const auto& s = c.snapshot();
        assert(s.playerRoster.size() == 2);
        // Join order, not sorted order: a tab list that reshuffles itself is
        // noise the player did not ask for.
        assert(s.playerRoster[0] == "Steve" && s.playerRoster[1] == "Alex");
        assert(s.rosterCount == 2);

        // A rename replaces in place; it must not move the player to the end.
        c.consume(playerList("c", 3, true, u1, "SteveRenamed"));
        assert(c.snapshot().playerRoster.size() == 2);
        assert(c.snapshot().playerRoster[0] == "SteveRenamed");
        assert(c.snapshot().playerRoster[1] == "Alex");

        // Removing someone who was never on the list changes nothing.
        const std::string u3(32, '3');
        c.consume(playerList("d", 4, false, u3, ""));
        assert(c.snapshot().playerRoster.size() == 2);

        c.consume(playerList("e", 5, false, u1, ""));
        assert(c.snapshot().playerRoster.size() == 1);
        assert(c.snapshot().playerRoster[0] == "Alex");

        // Factories refuse what would put a blank or unkeyable row on screen.
        assert(!makePlayerList("x", 6, true, "tooshort", "Steve").has_value());
        assert(!makePlayerList("x", 6, true, std::string(32, '4'), "").has_value());
        assert(!makePlayerList("", 6, true, u1, "Steve").has_value());
        // A removal legitimately carries no name.
        assert(makePlayerList("x", 6, false, u1, "").has_value());
    }

    // The roster is bounded: a session that joins and leaves all day must not
    // grow the snapshot without limit.
    {
        ObservationConsumer c;
        const char* hexDigits = "0123456789abcdef";
        for (std::size_t i = 0; i < 500; ++i) {
            // A distinct uuid per entry: reusing a handful of them would keep
            // the roster small for a reason that has nothing to do with bounds.
            std::string u(32, '0');
            u[30] = hexDigits[i % 16];
            u[31] = hexDigits[(i / 16) % 16];
            c.consume(playerList("j" + std::to_string(i), i, true, u, "P" + std::to_string(i)));
        }
        assert(c.snapshot().playerRoster.size() == 128);
        assert(c.snapshot().rosterCount == 500);
    }

    // --- connection facts: recorded once, absent before that ---
    {
        ObservationConsumer c;
        assert(!c.snapshot().latestConnection.has_value());
        // The factory refuses anything that would render as a real address.
        assert(!makeConnection("x", 1, "", 19132, 800).has_value());
        assert(!makeConnection("x", 1, "host", 0, 800).has_value());
        assert(!makeConnection("x", 1, "host", 19132, 0).has_value());
        c.consume(*makeConnection("y", 2, "mc.example.org", 19132, 800));
        const auto& s = c.snapshot();
        assert(s.latestConnection.has_value());
        assert(s.latestConnection->host == "mc.example.org");
        assert(s.latestConnection->port == 19132);
        assert(s.latestConnection->protocolVersion == 800);
    }

    std::cout << "test_observation_consumer: PASS\n";
    return 0;
}
