// Host unit test: read-only runtime event observation model (Stage 10).
// Synthetic sanitized fixtures shaped like Stage-9P observations; no raw
// capture data. Pure C++, deterministic, no I/O.
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <string>
#include <type_traits>

#include "xykell/runtime_event_observation.h"

using namespace xykell::runtime;

namespace {

const double QNAN = std::numeric_limits<double>::quiet_NaN();
const double INF = std::numeric_limits<double>::infinity();

} // namespace

int main() {
    // --- PlayerMessage: valid observation preserves every field ---
    {
        auto o = makePlayerMessage("xykell-obs-1", 1720000000001ULL, "TestPlayer", "hello");
        assert(o.has_value());
        assert(o->eventId == "xykell-obs-1");
        assert(o->observedAtMs == 1720000000001ULL);  // timestamp preserved
        assert(o->sender == "TestPlayer");            // sender preserved
        assert(o->message == "hello");                // message preserved
        RuntimeObservation v = *o;
        assert(kindOf(v) == ObservationKind::PlayerMessage);
        assert(std::holds_alternative<PlayerMessageObservation>(v));
    }
    // --- PlayerMessage: invalid input rejected ---
    {
        assert(!makePlayerMessage("", 1ULL, "s", "m").has_value());  // empty id
        assert(!makePlayerMessage("id", 1ULL, "", "m").has_value());  // empty sender
        assert(!makePlayerMessage("id", 1ULL, "s", "").has_value());  // missing message
        assert(!makePlayerMessage("id", 1ULL, "", "").has_value());   // malformed
    }
    // --- PlayerTravel: valid observation preserves every field ---
    {
        auto o = makePlayerTravel("xykell-obs-2", 1720000000002ULL,
                                  Vec3{-485.5, 64.62, -370.83}, -7.31, 1.054, 0);
        assert(o.has_value());
        assert(o->eventId == "xykell-obs-2");
        assert(o->observedAtMs == 1720000000002ULL);  // timestamp preserved
        assert(o->position.x == -485.5 && o->position.y == 64.62 &&
               o->position.z == -370.83);            // position preserved
        assert(o->yawDegrees == -7.31);               // rotation (yaw-only) preserved
        assert(o->metersTravelled == 1.054);          // meters preserved
        assert(o->travelMethod == 0);                 // travelMethod preserved raw
        RuntimeObservation v = *o;
        assert(kindOf(v) == ObservationKind::PlayerTravel);
        // Second observed travelMethod value passes through unmapped.
        auto t2 = makePlayerTravel("xykell-obs-3", 3ULL, Vec3{}, 0.0, 1.0, 2);
        assert(t2.has_value() && t2->travelMethod == 2);
    }
    // --- PlayerTravel: invalid numerics / geometry rejected ---
    {
        assert(!makePlayerTravel("", 1ULL, Vec3{}, 0.0, 1.0, 0).has_value());  // empty id
        assert(!makePlayerTravel("id", 1ULL, Vec3{QNAN, 0, 0}, 0.0, 1.0, 0)
                    .has_value());  // invalid position
        assert(!makePlayerTravel("id", 1ULL, Vec3{}, INF, 1.0, 0)
                    .has_value());  // invalid rotation
        assert(!makePlayerTravel("id", 1ULL, Vec3{}, 0.0, QNAN, 0)
                    .has_value());  // invalid meters
        assert(!makePlayerTravel("id", 1ULL, Vec3{}, 0.0, -0.5, 0)
                    .has_value());  // negative meters
    }
    // --- Unknown: metadata only, no payload surface ---
    {
        auto o = makeUnknown("xykell-obs-4", 4ULL, 404, "non-json-plaintext");
        assert(o.has_value());
        assert(o->wireLength == 404);
        assert(o->reason == "non-json-plaintext");
        RuntimeObservation v = *o;
        assert(kindOf(v) == ObservationKind::Unknown);
        assert(!makeUnknown("", 1ULL, 1, "r").has_value());  // empty id
        assert(!makeUnknown("id", 1ULL, 1, "").has_value());  // empty reason
    }
    // --- Source boundary: read-only, null source silent ---
    {
        static_assert(std::is_abstract<ObservationSource>::value,
                      "boundary must stay abstract (no direct construction)");
        static_assert(!std::is_default_constructible<ObservationSource>::value,
                      "boundary must not be directly constructible");
        NullObservationSource src;
        ObservationSource& ref = src;
        assert(ref.poll().empty());  // no provider attached -> no observations
    }

    std::cout << "test_runtime_observation: PASS\n";
    return 0;
}
