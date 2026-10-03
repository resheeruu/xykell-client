#pragma once

// Read-only observation source contract (Stage 13). Pure C++; no Android,
// no network, no crypto, no game, no authentication, no threads.
//
// RELATIONSHIP TO STAGE 10: runtime_event_observation.h already defines a
// batch-pull `ObservationSource` (`poll()` -> vector). That interface
// stays untouched. This header adds the ITEM-level contract the batch
// form cannot express: the distinction between "no observation right
// now", "here is one observation", and "source unavailable". Both are
// pull-style and read-only; neither has callbacks, transmit methods, or
// control surface.
//
// Ownership direction (never circular):
//   PollingSource -> RuntimeObservation -> ObservationConsumer.
// The source never owns, sees, or calls back into a consumer.
#include <cstdint>
#include <vector>

#include "xykell/runtime_event_observation.h"

namespace xykell::runtime {

// Factual source availability. Never claims a Minecraft connection:
// with no production source attached, a source reports Unavailable.
enum class SourceStatus : std::uint8_t {
    Unavailable = 0,
    Ready,
};

// Per-item poll outcome. Errors are states, never fake observations:
// no outcome ever synthesizes a PlayerMessage/PlayerTravelled.
enum class PollOutcome : std::uint8_t {
    NoObservation = 0,  // source fine, nothing available
    Observation,        // `observation` holds one item
    Unavailable,        // source unavailable; `observation` untouched
};

struct SourcePollResult {
    PollOutcome outcome = PollOutcome::NoObservation;
    RuntimeObservation observation;  // meaningful ONLY when outcome == Observation

    static SourcePollResult none() { return {PollOutcome::NoObservation, {}}; }
    static SourcePollResult unavailable() { return {PollOutcome::Unavailable, {}}; }
    static SourcePollResult item(RuntimeObservation o) {
        return {PollOutcome::Observation, std::move(o)};
    }
};

// Item-level read-only source contract. Synchronous, deterministic, single
// item per call. No send/execute/inject/write/request surface exists or
// may be added here.
class PollingSource {
  public:
    virtual ~PollingSource() = default;
    virtual SourceStatus status() const = 0;
    virtual SourcePollResult pollNext() = 0;
};

// Deterministic null source: always Unavailable, offline by construction.
// Allocates nothing, performs no I/O, uses no network/clock/randomness,
// contains no Minecraft logic. Safe empty source for testing.
class NullSource final : public PollingSource {
  public:
    SourceStatus status() const override { return SourceStatus::Unavailable; }
    SourcePollResult pollNext() override { return SourcePollResult::unavailable(); }
};

} // namespace xykell::runtime
