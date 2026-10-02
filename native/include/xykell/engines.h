#pragma once

// Engine abstractions (Batch 4/master §24-27). Pure data + safety logic.
// No game memory access, no hooks, no packets. Every operation is gated on
// runtime capabilities: without them the engine refuses with a reason instead
// of fabricating behavior. Providers are injected when verified sources exist.
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "xykell/runtime_probe.h"

namespace xykell::engines {

// ---- Target selection (combat support, no actions) ----
struct TargetCandidate {
    std::string id;
    double distance = 0.0;
    double angleDeg = 0.0;
    bool isFriend = false;
    bool isBot = false;
};

struct TargetConfig {
    double maxRange = 4.5;
    double maxAngleDeg = 30.0;
    bool ignoreFriends = true;
    bool ignoreBots = true;
};

struct TargetPick {
    bool found = false;
    std::string id;
    std::string reason; // why none / why this one
};

// Pure priority pick over caller-supplied candidates. Caller owns sourcing.
TargetPick pickTarget(const std::vector<TargetCandidate>& candidates,
                      const TargetConfig& cfg);

// ---- Movement state (data + validation, no writes) ----
struct MovementState {
    double vx = 0.0, vy = 0.0, vz = 0.0; // blocks/sec
    bool sprinting = false;
    bool flying = false;
    std::string mode = "vanilla";
};

struct MovementCheck {
    bool sane = true;
    std::string reason;
};

// Rejects absurd states (NaN/inf/speed beyond hard cap) before anything
// downstream could act on them.
MovementCheck validateMovement(const MovementState& s, double hardSpeedCap = 50.0);

// ---- World query budget (read-only queries, throttled by design) ----
struct WorldQuery {
    double x = 0.0, y = 0.0, z = 0.0;
    double radius = 16.0;
    int entityLimit = 32;
    int chunkLimit = 9;
};

struct Budget {
    double rangeLimit = 48.0;
    int entityLimit = 64;
    int chunkLimit = 25;
};

struct BudgetCheck {
    bool allowed = true;
    std::string reason;
};

BudgetCheck checkBudget(const WorldQuery& q, const Budget& b);

// ---- Performance budget (real measurement utility) ----
class FrameBudget {
  public:
    explicit FrameBudget(double budgetMs = 16.7) : budgetMs_(budgetMs) {}

    void recordFrame(double ms);
    double average() const;
    double worst() const;
    std::size_t samples() const { return samples_; }
    // True when the rolling average exceeds budget (throttle advice).
    bool overBudget() const;

  private:
    double budgetMs_;
    double total_ = 0.0;
    double worst_ = 0.0;
    std::size_t samples_ = 0;
};

// Gate helper shared by engines: modules compose services only when allowed.
runtime::GateResult engineGate(const std::vector<std::string>& needed,
                               const runtime::ProbeReport& probe);

} // namespace xykell::engines
