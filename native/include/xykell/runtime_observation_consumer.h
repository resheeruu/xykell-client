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
#include <cmath>
#include <cstdint>
#include <optional>
#include <string>
#include <utility>

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
    std::uint64_t vitalsCount = 0;
    std::uint64_t populationCount = 0;
    std::uint64_t lastObservedAtMs = 0;
    // Unknown metadata only: wire length + reason. No payload retained.
    std::uint64_t lastUnknownWireLength = 0;
    std::string lastUnknownReason;
    // Speed between the last two observed travel positions (m/s). Derived,
    // bounded (one optional double), observed — absent until two travel
    // samples with distinct timestamps exist. Event-rate limited: it is the
    // average between observations, not a continuous reading.
    std::optional<double> speedMps;
    // Latest observed vitals. Separate optionals, never defaulted to zero: the
    // HUD must be able to say "health unknown" rather than render an empty bar.
    std::optional<int> latestHealth;
    std::optional<int> latestTimeTicks;
    // Observed ticks-per-second, derived from two SetTime samples the same way
    // speedMps is: absent until two clock readings at distinct timestamps
    // exist. Server-reported cadence, not a measured frame rate.
    std::optional<double> ticksPerSecond;
    // Latest observed entity counts. Absent = the relay has not reported yet.
    std::optional<std::uint64_t> latestEntityCount;
    std::optional<std::uint64_t> latestPlayerCount;
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
            if (prevTravel_.has_value() && t->observedAtMs > prevTravel_->observedAtMs) {
                const double dx = t->position.x - prevTravel_->position.x;
                const double dy = t->position.y - prevTravel_->position.y;
                const double dz = t->position.z - prevTravel_->position.z;
                const double dist = std::sqrt(dx * dx + dy * dy + dz * dz);
                const std::uint64_t dt = t->observedAtMs - prevTravel_->observedAtMs;
                snapshot_.speedMps = dist * 1000.0 / static_cast<double>(dt);
            }
            // Equal or backward timestamps keep the last valid speed (no
            // division by zero, no negative speed from out-of-order events).
            prevTravel_ = *t;  // value copy, bounded to one entry
            snapshot_.latestTravel = *t;  // value copy
            ++snapshot_.travelCount;
            snapshot_.lastObservedAtMs = t->observedAtMs;
        } else if (const auto* v = std::get_if<VitalsObservation>(&observation)) {
            // Each field is merged independently, so a SetTime-only observation
            // never clears a health the server already reported.
            if (v->health.has_value()) {
                snapshot_.latestHealth = *v->health;
            }
            if (v->timeTicks.has_value()) {
                // TPS is derived the same way speedMps is: between the previous
                // clock reading and this one. Equal or backward ticks keep the
                // last valid value, so a stalled or rewound clock cannot divide
                // by zero or report a negative rate.
                if (prevTicks_.has_value() && v->observedAtMs > prevTicksAtMs_ &&
                    *v->timeTicks > *prevTicks_) {
                    const double dt = static_cast<double>(v->observedAtMs - prevTicksAtMs_);
                    snapshot_.ticksPerSecond =
                        static_cast<double>(*v->timeTicks - *prevTicks_) * 1000.0 / dt;
                }
                prevTicks_ = *v->timeTicks;
                prevTicksAtMs_ = v->observedAtMs;
                snapshot_.latestTimeTicks = *v->timeTicks;
            }
            ++snapshot_.vitalsCount;
            snapshot_.lastObservedAtMs = v->observedAtMs;
        } else if (const auto* p = std::get_if<EntityPopulationObservation>(&observation)) {
            snapshot_.latestEntityCount = p->entityCount;
            snapshot_.latestPlayerCount = p->playerCount;
            ++snapshot_.populationCount;
            snapshot_.lastObservedAtMs = p->observedAtMs;
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
        return snapshot_.messageCount + snapshot_.travelCount + snapshot_.unknownCount +
               snapshot_.vitalsCount + snapshot_.populationCount;
    }

  private:
    RuntimeObservationSnapshot snapshot_;
    // Previous travel sample for the speed delta. Exactly one entry:
    // boundedness rule (no history list) still holds.
    std::optional<PlayerTravelObservation> prevTravel_;
    // Previous SetTime sample for the ticks-per-second delta. Exactly one entry:
    // the no-history boundedness rule still holds.
    std::optional<int> prevTicks_;
    std::uint64_t prevTicksAtMs_ = 0;
};

// Process-wide consumer: fed by the JNI observation offers (app side) and
// read by the HUD bind path. One instance per process — the app process has
// the fed copy, the game process stays empty until a game-side feed exists.
ObservationConsumer& sharedObservationConsumer();

inline const RuntimeObservationSnapshot& sharedObservationSnapshot() {
    return sharedObservationConsumer().snapshot();
}

} // namespace xykell::runtime
