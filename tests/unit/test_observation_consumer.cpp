// Host unit test: read-only observation consumer (Stage 12).
// Synthetic Stage-10-schema fixtures only; never Minecraft data.
// Pure C++, deterministic, no I/O.
#include <cassert>
#include <iostream>
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
    // --- boundedness: fixed-size state regardless of volume ---
    {
        ObservationConsumer c;
        for (int i = 0; i < 1000; ++i) {
            c.consume(RuntimeObservation{msg("m", 1ULL, "x")});
            c.consume(RuntimeObservation{travel("t", 1ULL, 1.0, 0)});
            c.consume(RuntimeObservation{unknown("u", 1ULL, 9)});
        }
        const auto& s = c.snapshot();
        assert(s.messageCount == 1000 && s.travelCount == 1000 && s.unknownCount == 1000);
        assert(c.totalConsumed() == 3000);
        // Constant shape: two optionals + five scalars + one short string.
        assert(sizeof(RuntimeObservationSnapshot) < 512);
    }

    std::cout << "test_observation_consumer: PASS\n";
    return 0;
}
