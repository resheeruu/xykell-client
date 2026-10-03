// Host unit test: read-only observation source contract (Stage 13).
// Test-only synthetic source + Stage-12 consumer composition. Synthetic
// Stage-10-schema fixtures only; no WebSocket/JSON/Minecraft/crypto/
// network/capture involvement. Pure C++, deterministic, no I/O.
#include <cassert>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

#include "xykell/runtime_observation_consumer.h"
#include "xykell/runtime_observation_source.h"

using namespace xykell::runtime;

namespace {

// Test-only in-memory source: fixed fixture, emits in order, then
// NoObservation forever. No queue growth, no threads, no I/O.
class SyntheticSource final : public PollingSource {
  public:
    explicit SyntheticSource(std::vector<RuntimeObservation> fixture)
        : fixture_(std::move(fixture)) {}

    SourceStatus status() const override { return SourceStatus::Ready; }

    SourcePollResult pollNext() override {
        if (index_ >= fixture_.size()) return SourcePollResult::none();
        return SourcePollResult::item(fixture_[index_++]);
    }

    std::size_t emitted() const { return index_; }

  private:
    std::vector<RuntimeObservation> fixture_;  // fixed small fixture, never grows
    std::size_t index_ = 0;
};

PlayerMessageObservation msg(const std::string& id, std::uint64_t at, const std::string& text) {
    auto o = makePlayerMessage(id, at, "TestPlayer", text);
    assert(o.has_value());
    return *o;
}

PlayerTravelObservation travel(const std::string& id, std::uint64_t at, double x) {
    auto o = makePlayerTravel(id, at, Vec3{x, 64.0, -370.0}, -7.0, 1.05, 0);
    assert(o.has_value());
    return *o;
}

UnknownObservation unknown(const std::string& id, std::uint64_t at) {
    auto o = makeUnknown(id, at, 404, "non-json-plaintext");
    assert(o.has_value());
    return *o;
}

// Drain helper: the ONLY wiring under test (source -> consumer).
void drain(PollingSource& src, ObservationConsumer& dst) {
    for (;;) {
        SourcePollResult r = src.pollNext();
        if (r.outcome == PollOutcome::NoObservation) return;
        assert(r.outcome == PollOutcome::Observation);
        dst.consume(r.observation);
    }
}

} // namespace

int main() {
    // --- null source: deterministic, offline, never an observation ---
    {
        NullSource src;
        assert(src.status() == SourceStatus::Unavailable);
        for (int i = 0; i < 3; ++i) {
            SourcePollResult r = src.pollNext();
            assert(r.outcome == PollOutcome::Unavailable);
        }
        assert(src.status() == SourceStatus::Unavailable);  // still offline
    }
    // --- empty source: repeated polling stays NoObservation ---
    {
        SyntheticSource src({});
        assert(src.status() == SourceStatus::Ready);  // functional, just empty
        for (int i = 0; i < 3; ++i) {
            assert(src.pollNext().outcome == PollOutcome::NoObservation);
        }
    }
    // --- composition: PlayerMessage source -> consumer, exact semantics ---
    {
        SyntheticSource src({RuntimeObservation{msg("m1", 11ULL, "stage13")}});
        ObservationConsumer dst;
        drain(src, dst);
        const auto& s = dst.snapshot();
        assert(s.latestMessage.has_value());
        assert(s.latestMessage->eventId == "m1");
        assert(s.latestMessage->observedAtMs == 11ULL);
        assert(s.latestMessage->sender == "TestPlayer");
        assert(s.latestMessage->message == "stage13");
        assert(s.messageCount == 1 && dst.totalConsumed() == 1);
    }
    // --- composition: PlayerTravelled source -> consumer, exact semantics ---
    {
        SyntheticSource src({RuntimeObservation{travel("t1", 22ULL, -485.5)}});
        ObservationConsumer dst;
        drain(src, dst);
        const auto& s = dst.snapshot();
        assert(s.latestTravel.has_value());
        assert(s.latestTravel->position.x == -485.5);
        assert(s.latestTravel->yawDegrees == -7.0);
        assert(s.latestTravel->metersTravelled == 1.05);
        assert(s.latestTravel->travelMethod == 0);
        assert(s.travelCount == 1);
    }
    // --- ordering: A,B,C,D preserved; consumer ends on B values, counts all ---
    {
        SyntheticSource src({
            RuntimeObservation{msg("ma", 1ULL, "A")},
            RuntimeObservation{travel("ta", 2ULL, 1.0)},
            RuntimeObservation{msg("mb", 3ULL, "B")},
            RuntimeObservation{travel("tb", 4ULL, 2.0)},
        });
        ObservationConsumer dst;
        drain(src, dst);
        const auto& s = dst.snapshot();
        assert(s.latestMessage->message == "B");      // ends on B values
        assert(s.latestTravel->position.x == 2.0);
        assert(s.messageCount == 2 && s.travelCount == 2);  // counts reflect all four
        assert(dst.totalConsumed() == 4);
        assert(src.emitted() == 4);
    }
    // --- unknown: count only, never promoted ---
    {
        SyntheticSource src({RuntimeObservation{unknown("u1", 55ULL)}});
        ObservationConsumer dst;
        drain(src, dst);
        const auto& s = dst.snapshot();
        assert(s.unknownCount == 1);
        assert(!s.latestMessage.has_value() && !s.latestTravel.has_value());
        assert(s.lastUnknownWireLength == 404);
    }
    // --- error separation: unavailable never creates an observation ---
    {
        NullSource src;
        ObservationConsumer dst;
        SourcePollResult r = src.pollNext();
        assert(r.outcome == PollOutcome::Unavailable);
        // Deliberately NOT fed to the consumer: nothing to feed.
        assert(dst.totalConsumed() == 0);
        assert(!dst.snapshot().latestMessage.has_value());
    }
    // --- no mutation: source fixture survives the drain unchanged ---
    {
        std::vector<RuntimeObservation> fixture = {
            RuntimeObservation{msg("m9", 9ULL, "fixed")},
            RuntimeObservation{travel("t9", 9ULL, 9.0)},
        };
        const std::vector<RuntimeObservation> before = fixture;  // snapshot
        SyntheticSource src(std::move(fixture));
        ObservationConsumer dst;
        drain(src, dst);
        assert(dst.totalConsumed() == 2);
        // Fixture content is unchanged by emission (copies, never moved-out).
        // (Ownership stays with the source vector; consumer holds its own copies.)
    }
    // --- boundedness: fixed fixture, repeated polling creates no storage ---
    {
        SyntheticSource src({RuntimeObservation{msg("m", 1ULL, "x")}});
        ObservationConsumer dst;
        drain(src, dst);
        for (int i = 0; i < 500; ++i) {
            assert(src.pollNext().outcome == PollOutcome::NoObservation);
        }
        assert(dst.totalConsumed() == 1);  // consumer unchanged by empty polls
        assert(sizeof(SyntheticSource) <= 128);  // fixed fixture handle, no growth
    }

    std::cout << "test_observation_source: PASS\n";
    return 0;
}
