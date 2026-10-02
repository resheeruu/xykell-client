#include "xykell/engines.h"

#include <cmath>

namespace xykell::engines {

TargetPick pickTarget(const std::vector<TargetCandidate>& candidates,
                      const TargetConfig& cfg) {
    TargetPick best;
    double bestDist = 0.0;
    for (const auto& c : candidates) {
        if (!(c.distance >= 0.0) || !(c.angleDeg >= 0.0)) {
            continue; // NaN-safe: unordered comparisons are false
        }
        if (c.distance > cfg.maxRange || c.angleDeg > cfg.maxAngleDeg) {
            continue;
        }
        if (c.isFriend && cfg.ignoreFriends) {
            continue;
        }
        if (c.isBot && cfg.ignoreBots) {
            continue;
        }
        // Nearest valid wins; first-seen wins ties deterministically.
        if (!best.found || c.distance < bestDist) {
            best.found = true;
            best.id = c.id;
            bestDist = c.distance;
            best.reason = std::to_string(c.distance);
        }
    }
    if (!best.found) {
        best.reason = "no candidate within range/angle/filters";
    }
    return best;
}

MovementCheck validateMovement(const MovementState& s, double hardSpeedCap) {
    const double v[3] = {s.vx, s.vy, s.vz};
    for (const double c : v) {
        if (!std::isfinite(c)) {
            return {false, "non-finite velocity component"};
        }
    }
    const double speed = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    if (speed > hardSpeedCap) {
        return {false, "speed exceeds hard cap"};
    }
    if (s.mode.empty()) {
        return {false, "empty movement mode"};
    }
    return {true, "sane"};
}

BudgetCheck checkBudget(const WorldQuery& q, const Budget& b) {
    if (!(q.radius > 0.0) || q.radius > b.rangeLimit) {
        return {false, "radius outside budget"};
    }
    if (q.entityLimit <= 0 || q.entityLimit > b.entityLimit) {
        return {false, "entity limit outside budget"};
    }
    if (q.chunkLimit <= 0 || q.chunkLimit > b.chunkLimit) {
        return {false, "chunk limit outside budget"};
    }
    return {true, "within budget"};
}

void FrameBudget::recordFrame(double ms) {
    if (!(ms >= 0.0)) {
        return; // ignore garbage, keep stats clean
    }
    total_ += ms;
    if (ms > worst_) {
        worst_ = ms;
    }
    ++samples_;
}

double FrameBudget::average() const {
    return samples_ == 0 ? 0.0 : total_ / static_cast<double>(samples_);
}

double FrameBudget::worst() const { return worst_; }

bool FrameBudget::overBudget() const {
    return samples_ > 0 && average() > budgetMs_;
}

runtime::GateResult engineGate(const std::vector<std::string>& needed,
                               const runtime::ProbeReport& probe) {
    return runtime::canEnable(needed, probe, false);
}

} // namespace xykell::engines
