#pragma once

// Xykell-owned read-only observation model for the two Minecraft events
// verified by Stage 9P (PlayerMessage, PlayerTravelled). Pure C++; no
// Android, no network, no crypto, no JSON envelopes, no lab code.
//
// ARCHITECTURE RULE: production consumes these normalized types. Nothing
// here knows about WebSocket / wsserver / mcwss / P-384 / ECDH / SHA-256 /
// CFB8 / capture files. A future observation source translates verified
// source events into these types; no live connection exists in this stage.
//
// PROVENANCE: every type below is an OBSERVATION (something Xykell saw),
// never authoritative game state. Do not rename these to *State.
// `observedAtMs` is the time Xykell observed the event (observer clock),
// NOT the time the Minecraft user performed the underlying action
// (Stage 9P never clock-proved causality).
//
// Follows the runtime_provider.h "immutable observation snapshot"
// convention: plain data, no mutators, created only via make-functions
// (which reject invalid input with nullopt), published by value/const.
#include <cmath>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <variant>
#include <vector>

namespace xykell::runtime {

enum class ObservationKind : std::uint8_t {
    PlayerMessage = 0,
    PlayerTravel,
    Unknown,
};

// Observed chat line. 9P bodies always carry non-empty sender + message;
// `receiver` was always "" in evidence and is omitted as signal.
struct PlayerMessageObservation {
    std::string eventId;        // caller-supplied deterministic id, never empty
    std::uint64_t observedAtMs = 0;
    std::string sender;
    std::string message;
};

// Observed movement tick. Only yRot exists in evidence, so rotation is
// yaw-only (pitch unobserved -> omitted, not defaulted). travelMethod is
// the raw observed int (0 and 2 seen); its semantics are UNVERIFIED, so it
// is preserved opaquely and must not be mapped to walk/fly/swim/etc.
struct Vec3 {
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;
};

struct PlayerTravelObservation {
    std::string eventId;
    std::uint64_t observedAtMs = 0;
    Vec3 position;
    double yawDegrees = 0.0;
    double metersTravelled = 0.0;
    int travelMethod = 0;
};

// Generic unknown observation (e.g. lab U1/U0 frames). Metadata ONLY:
// wire length + short reason. Never payload, ciphertext, plaintext, or
// inferred meaning.
struct UnknownObservation {
    std::string eventId;
    std::uint64_t observedAtMs = 0;
    std::uint64_t wireLength = 0;
    std::string reason;  // e.g. "non-json-plaintext", "pre-establishment-binary"
};

using RuntimeObservation =
    std::variant<PlayerMessageObservation, PlayerTravelObservation, UnknownObservation>;

inline ObservationKind kindOf(const RuntimeObservation& o) {
    if (std::holds_alternative<PlayerMessageObservation>(o)) return ObservationKind::PlayerMessage;
    if (std::holds_alternative<PlayerTravelObservation>(o)) return ObservationKind::PlayerTravel;
    return ObservationKind::Unknown;
}

namespace detail {
inline bool validId(const std::string& id) { return !id.empty(); }
inline bool validNumber(double v) { return std::isfinite(v); }
} // namespace detail

// Factories: invalid input -> nullopt. metersTravelled must be a finite,
// non-negative measurement (sanity bound on the observation, not a game
// semantic); travelMethod passes through unvalidated (semantics unknown).
inline std::optional<PlayerMessageObservation> makePlayerMessage(
    const std::string& eventId, std::uint64_t observedAtMs, const std::string& sender,
    const std::string& message) {
    if (!detail::validId(eventId) || sender.empty() || message.empty()) return std::nullopt;
    return PlayerMessageObservation{eventId, observedAtMs, sender, message};
}

inline std::optional<PlayerTravelObservation> makePlayerTravel(
    const std::string& eventId, std::uint64_t observedAtMs, Vec3 position, double yawDegrees,
    double metersTravelled, int travelMethod) {
    if (!detail::validId(eventId)) return std::nullopt;
    if (!detail::validNumber(position.x) || !detail::validNumber(position.y) ||
        !detail::validNumber(position.z) || !detail::validNumber(yawDegrees)) {
        return std::nullopt;
    }
    if (!detail::validNumber(metersTravelled) || metersTravelled < 0.0) return std::nullopt;
    return PlayerTravelObservation{eventId, observedAtMs, position, yawDegrees, metersTravelled,
                                  travelMethod};
}

inline std::optional<UnknownObservation> makeUnknown(const std::string& eventId,
                                                     std::uint64_t observedAtMs,
                                                     std::uint64_t wireLength,
                                                     const std::string& reason) {
    if (!detail::validId(eventId) || reason.empty()) return std::nullopt;
    return UnknownObservation{eventId, observedAtMs, wireLength, reason};
}

// Read-only observation source boundary: poll() yields already-normalized
// observations. Deliberately method-minimal — no send/execute/inject/write
// surface exists or may be added here. No live implementation in this stage.
class ObservationSource {
  public:
    virtual ~ObservationSource() = default;
    virtual std::vector<RuntimeObservation> poll() = 0;
};

// Null source: always empty. Proves the boundary compiles and stays silent
// with no provider attached.
class NullObservationSource final : public ObservationSource {
  public:
    std::vector<RuntimeObservation> poll() override { return {}; }
};

} // namespace xykell::runtime
