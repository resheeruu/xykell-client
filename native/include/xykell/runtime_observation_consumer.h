#pragma once

// Read-only runtime observation consumer (Stage 12). Pure C++; no
// Android, no network, no crypto, no game, no authentication.
//
// Accepts Xykell-owned observations (runtime_event_observation.h) into
// a bounded in-memory snapshot. The consumer is a SINK: it has no
// send/execute/inject/write/command/control surface, cannot request
// anything from a source, and cannot alter one. It never sees raw JSON,
// envelopes, ciphertext, or lab code — only already-validated model
// objects (invalid input cannot reach this boundary; that is the
// lab/translator's responsibility).
//
// Memory is strictly bounded: latest message, latest travel, fixed-size
// counters, last-unknown metadata. No history list, no persistence, no
// file or network I/O. Phone-first: sizeof state is constant.
//
// State semantics are OBSERVED, never authoritative: an empty snapshot
// means "nothing observed", with no fabricated position/sender/message.
#include <cstdint>
#include <optional>
#include <string>

#include "xykell/runtime_event_observation.h"

namespace xykell::runtime {

// Bounded read-only snapshot of consumed observations. Plain data, no
// mutators; only ObservationConsumer writes it (by value copy).
struct RuntimeObservationSnapshot {
    std::optional<PlayerMessageObservation> latestMessage;
    std::optional<PlayerTravelObservation> latestTravel;
    std::uint64_t messageCount = 0;
    std::uint64_t travelCount = 0;
    std::uint64_t unknownCount = 0;
    std::uint64_t lastObservedAtMs = 0;
    // Unknown metadata only: wire length + reason. No payload retained.
    std::uint64_t lastUnknownWireLength = 0;
    std::string lastUnknownReason;
};

// Single-method consumer boundary. Copy-in/copy-out by value: stored
// state can never alias caller memory, so caller-side mutation after
// consume() cannot alter the snapshot.
class ObservationConsumer {
  public:
    void consume(const RuntimeObservation& observation) {
        if (const auto* m = std::get_if<PlayerMessageObservation>(&observation)) {
            snapshot_.latestMessage = *m;  // value copy
            ++snapshot_.messageCount;
            snapshot_.lastObservedAtMs = m->observedAtMs;
        } else if (const auto* t = std::get_if<PlayerTravelObservation>(&observation)) {
            snapshot_.latestTravel = *t;  // value copy
            ++snapshot_.travelCount;
            snapshot_.lastObservedAtMs = t->observedAtMs;
        } else if (const auto* u = std::get_if<UnknownObservation>(&observation)) {
            // Unknown observations are COUNTED, never promoted: no message
            // or travel state is created, and no payload is retained.
            ++snapshot_.unknownCount;
            snapshot_.lastObservedAtMs = u->observedAtMs;
            snapshot_.lastUnknownWireLength = u->wireLength;
            snapshot_.lastUnknownReason = u->reason;
        }
    }

    const RuntimeObservationSnapshot& snapshot() const { return snapshot_; }

    std::uint64_t totalConsumed() const {
        return snapshot_.messageCount + snapshot_.travelCount + snapshot_.unknownCount;
    }

  private:
    RuntimeObservationSnapshot snapshot_;
};

} // namespace xykell::runtime
