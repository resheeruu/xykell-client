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
    // Vitals the relay actually reads off the wire: SetHealth 0x2A and SetTime
    // 0x0A, both verified layouts (BedrockPackets). No armour, hunger or effect
    // field exists here because none of those is decoded yet.
    Vitals,
    // How many entities the relay currently tracks. A COUNT of what the relay
    // saw, never a claim about the world: an entity it has not been told about
    // does not appear here.
    EntityPopulation,
    // One add/remove of an online player, from PlayerList 0x3f.
    PlayerList,
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

// Observed vitals. Both fields are OPTIONAL and that is the whole point: the
// server sends SetHealth and SetTime on different cadences, so "health seen,
// clock not yet" is a real state and must not be rendered as a zero.
//
// health is in Bedrock's half-heart units as they arrive on the wire (the
// server sends 20 for a full bar). No unit conversion happens here: the HUD
// shows what the server reported, and nothing infers a maximum it was not told.
struct VitalsObservation {
    std::string eventId;
    std::uint64_t observedAtMs = 0;
    std::optional<int> health;    // absent = never observed
    std::optional<int> timeTicks; // absent = never observed
};

// Observed entity population, from the relay's bounded entity table.
struct EntityPopulationObservation {
    std::string eventId;
    std::uint64_t observedAtMs = 0;
    std::uint64_t entityCount = 0;  // every tracked runtime id
    std::uint64_t playerCount = 0; // the subset flagged as a player
};

// One entry of the online roster, from PlayerList 0x3f.
//
// Only the UUID and the name are retained: the rest of a real entry (skin data,
// device flags, the title blob) is version-specific and is not read. `present`
// is false for a removal, and the uuid is then all that is meaningful.
struct PlayerListObservation {
    std::string eventId;
    std::uint64_t observedAtMs = 0;
    bool present = false;
    std::string uuid; // 32 lowercase hex characters
    std::string name; // empty when !present
};

using RuntimeObservation = std::variant<PlayerMessageObservation, PlayerTravelObservation,
                                        UnknownObservation, VitalsObservation,
                                        EntityPopulationObservation,
                                        PlayerListObservation>;

inline ObservationKind kindOf(const RuntimeObservation& o) {
    if (std::holds_alternative<PlayerMessageObservation>(o)) return ObservationKind::PlayerMessage;
    if (std::holds_alternative<PlayerTravelObservation>(o)) return ObservationKind::PlayerTravel;
    if (std::holds_alternative<VitalsObservation>(o)) return ObservationKind::Vitals;
    if (std::holds_alternative<EntityPopulationObservation>(o)) {
        return ObservationKind::EntityPopulation;
    }
    if (std::holds_alternative<PlayerListObservation>(o)) {
        return ObservationKind::PlayerList;
    }
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

// Rejects a Vitals observation that observed NEITHER value: that is an empty
// event that would only ever overwrite a good snapshot with nothing. One field
// alone is valid, because the two arrive on independent cadences.
inline std::optional<VitalsObservation> makeVitals(const std::string& eventId,
                                                   std::uint64_t observedAtMs,
                                                   std::optional<int> health,
                                                   std::optional<int> timeTicks) {
    if (!detail::validId(eventId)) return std::nullopt;
    if (!health.has_value() && !timeTicks.has_value()) return std::nullopt;
    if (health.has_value() && *health < 0) return std::nullopt;
    if (timeTicks.has_value() && *timeTicks < 0) return std::nullopt;
    return VitalsObservation{eventId, observedAtMs, health, timeTicks};
}

// Rejects a population with players > entities: that can only be a caller bug,
// and accepting it would let the HUD render an impossible pair.
inline std::optional<EntityPopulationObservation> makeEntityPopulation(
    const std::string& eventId, std::uint64_t observedAtMs, std::uint64_t entityCount,
    std::uint64_t playerCount) {
    if (!detail::validId(eventId)) return std::nullopt;
    if (playerCount > entityCount) return std::nullopt;
    return EntityPopulationObservation{eventId, observedAtMs, entityCount, playerCount};
}

// A removal needs only a uuid; an add needs a name as well, because a roster
// entry with no name would render a blank row that looks like a real player.
// The uuid is length-checked (32 hex chars) rather than merely non-empty, since
// it is the roster's key.
inline std::optional<PlayerListObservation> makePlayerList(const std::string& eventId,
                                                           std::uint64_t observedAtMs,
                                                           bool present,
                                                           const std::string& uuid,
                                                           const std::string& name) {
    if (!detail::validId(eventId)) return std::nullopt;
    if (uuid.size() != 32) return std::nullopt;
    if (present && name.empty()) return std::nullopt;
    return PlayerListObservation{eventId, observedAtMs, present, uuid, present ? name : std::string()};
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
