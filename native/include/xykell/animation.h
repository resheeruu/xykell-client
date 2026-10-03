#pragma once

// Unified animation engine (Batch 3). Pure C++, deterministic injected
// time, no platform, no timers of its own. One system drives startup,
// page transitions, module toggles, HUD editing, lists, and toasts —
// never hundreds of ad-hoc timers.
//
// Accessibility: reduced-motion mode collapses every animation to its
// end state instantly. Performance mode shortens durations. A global
// speed multiplier scales time for all running animations.
#include <cmath>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace xykell::anim {

enum class Ease {
    Linear,
    FadeIn,    // smoothstep in
    FadeOut,   // smoothstep out
    SlideIn,   // ease-out cubic (decelerating entrance)
    SlideOut,  // ease-in cubic (accelerating exit)
    Scale,     // ease-out back (subtle overshoot, clamped)
    Spring,    // damped oscillation around 1.0
};

inline double clamp01(double t) {
    if (t < 0.0) {
        return 0.0;
    }
    if (t > 1.0) {
        return 1.0;
    }
    return t;
}

// Normalized progress [0,1] -> eased value. Spring may overshoot above
// 1.0 by design (callers clamp for opacity/positions as needed).
inline double ease(Ease kind, double t) {
    t = clamp01(t);
    switch (kind) {
        case Ease::Linear:
            return t;
        case Ease::FadeIn:
            return t * t * (3.0 - 2.0 * t);
        case Ease::FadeOut:
            return 1.0 - t * t * (3.0 - 2.0 * t);
        case Ease::SlideIn: {
            const double u = 1.0 - t;
            return 1.0 - u * u * u;
        }
        case Ease::SlideOut:
            return t * t * t;
        case Ease::Scale: {
            // Gentle ease-out-back: 0 -> 1 with a ~3% overshoot near the end.
            const double u = t - 1.0;
            return 1.0 + 1.5 * u * u * u + 0.5 * u * u;
        }
        case Ease::Spring: {
            // Underdamped: settles at 1.0, oscillates with decay.
            if (t >= 1.0) {
                return 1.0;
            }
            const double w = 12.0 * t;
            return 1.0 - std::exp(-6.0 * t) * std::cos(w);
        }
    }
    return t;
}

struct AnimationSpec {
    std::string id;
    Ease ease = Ease::FadeIn;
    std::int64_t durationMs = 250;  // before speed scaling
    std::int64_t delayMs = 0;       // start offset (staggering)
};

struct AnimationState {
    AnimationSpec spec;
    std::int64_t startMs = 0;  // host clock at start()
    bool finished = false;

    // Progress in [0,1] at host time nowMs (before easing).
    double progress(std::int64_t nowMs, double speed) const {
        if (speed <= 0.0) {
            return 1.0;
        }
        const double scaled = static_cast<double>(spec.durationMs) / speed;
        if (scaled <= 0.0) {
            return 1.0;
        }
        const double elapsed = static_cast<double>(nowMs - startMs - spec.delayMs);
        if (elapsed <= 0.0) {
            return 0.0;
        }
        return clamp01(elapsed / scaled);
    }

    double value(std::int64_t nowMs, double speed, bool reducedMotion) const {
        if (reducedMotion) {
            return 1.0;  // accessibility: jump to end state
        }
        return ease(spec.ease, progress(nowMs, speed));
    }
};

class AnimationController {
  public:
    void configure(bool reducedMotion, bool performanceMode, double speed) {
        reducedMotion_ = reducedMotion;
        performanceMode_ = performanceMode;
        speed_ = speed > 0.0 ? speed : 1.0;
    }

    bool reducedMotion() const { return reducedMotion_; }

    // Effective duration: performance mode halves everything.
    std::int64_t effectiveDurationMs(const AnimationSpec& spec) const {
        std::int64_t d = spec.durationMs;
        if (performanceMode_) {
            d /= 2;
        }
        if (d < 0) {
            d = 0;
        }
        return d;
    }

    void start(const AnimationSpec& spec, std::int64_t nowMs) {
        AnimationState st;
        st.spec = spec;
        if (performanceMode_) {
            st.spec.durationMs = effectiveDurationMs(spec);
        }
        st.startMs = nowMs;
        st.finished = false;
        running_.push_back(st);
    }

    // Advances all animations; returns finished ids and drops them.
    // Finished = reached end state (or reduced-motion collapse).
    std::vector<std::string> update(std::int64_t nowMs) {
        std::vector<std::string> done;
        std::vector<AnimationState> keep;
        for (auto& st : running_) {
            const double p = st.progress(nowMs, speed_);
            if (reducedMotion_ || p >= 1.0) {
                st.finished = true;
                done.push_back(st.spec.id);
            } else {
                keep.push_back(st);
            }
        }
        running_ = std::move(keep);
        return done;
    }

    double value(const std::string& id, std::int64_t nowMs) const {
        for (const auto& st : running_) {
            if (st.spec.id == id) {
                return st.value(nowMs, speed_, reducedMotion_);
            }
        }
        return 1.0;  // not running (or finished): end state
    }

    std::size_t running() const { return running_.size(); }

    void cancelAll() { running_.clear(); }

    // Staggered list helper: assigns increasing delays, returns specs.
    static std::vector<AnimationSpec> stagger(const std::vector<std::string>& ids, Ease kind,
                                              std::int64_t durationMs, std::int64_t stepMs) {
        std::vector<AnimationSpec> out;
        for (std::size_t i = 0; i < ids.size(); ++i) {
            AnimationSpec s;
            s.id = ids[i];
            s.ease = kind;
            s.durationMs = durationMs;
            s.delayMs = static_cast<std::int64_t>(i) * stepMs;
            out.push_back(s);
        }
        return out;
    }

  private:
    std::vector<AnimationState> running_;
    bool reducedMotion_ = false;
    bool performanceMode_ = false;
    double speed_ = 1.0;
};

} // namespace xykell::anim
