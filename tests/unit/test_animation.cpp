// Host unit test: unified animation engine (Batch 3). Deterministic
// injected time only; no platform, no timers.
#include <cassert>
#include <cmath>
#include <iostream>
#include <string>

#include "xykell/animation.h"

using namespace xykell::anim;

namespace {

bool near(double a, double b, double eps = 1e-9) {
    return std::fabs(a - b) <= eps;
}

} // namespace

int main() {
    // --- easing endpoints and shapes ---
    {
        for (int k = 0; k <= 6; ++k) {
            const Ease kind = static_cast<Ease>(k);
            if (kind == Ease::FadeOut) {
                assert(near(ease(kind, 0.0), 1.0));  // fade-out starts visible
                assert(near(ease(kind, 1.0), 0.0));
            } else if (kind == Ease::Spring) {
                assert(near(ease(kind, 0.0), 0.0));
                assert(near(ease(kind, 1.0), 1.0));
            } else {
                assert(near(ease(kind, 0.0), 0.0));
                assert(near(ease(kind, 1.0), 1.0, 1e-6));
            }
            assert(ease(kind, -5.0) == ease(kind, 0.0));  // clamped input
            assert(ease(kind, 99.0) == ease(kind, 1.0));
        }
        assert(ease(Ease::Linear, 0.3) == 0.3);
        assert(ease(Ease::SlideIn, 0.5) > 0.5);   // decelerating entrance
        assert(ease(Ease::SlideOut, 0.5) < 0.5);  // accelerating exit
        const double peak = ease(Ease::Scale, 0.8);
        assert(peak > 1.0 && peak < 1.1);  // documented ~3% overshoot
        const double springMid = ease(Ease::Spring, 0.3);
        assert(springMid > 0.0);  // oscillates; just must be in motion
    }
    // --- animation progress/value lifecycle ---
    {
        AnimationController ctl;
        AnimationSpec s;
        s.id = "fade";
        s.ease = Ease::FadeIn;
        s.durationMs = 200;
        ctl.start(s, 1000);
        assert(near(ctl.value("fade", 1000), 0.0));
        assert(near(ctl.value("fade", 1100), 0.5));
        assert(near(ctl.value("fade", 1200), 1.0));
        assert(ctl.running() == 1);
        const auto done = ctl.update(1200);
        assert(done.size() == 1 && done[0] == "fade");
        assert(ctl.running() == 0);
        assert(near(ctl.value("fade", 9999), 1.0));  // finished: end state
    }
    // --- delay/stagger ---
    {
        AnimationController ctl;
        auto specs = AnimationController::stagger({"a", "b", "c"}, Ease::FadeIn, 100, 50);
        assert(specs.size() == 3 && specs[2].delayMs == 100);
        for (const auto& sp : specs) {
            ctl.start(sp, 0);
        }
        assert(near(ctl.value("c", 25), 0.0));  // not started (delay 100)
        assert(ctl.update(25).empty());
        assert(ctl.update(1000).size() == 3);  // all finish eventually
    }
    // --- reduced motion collapses everything ---
    {
        AnimationController ctl;
        ctl.configure(true, false, 1.0);
        AnimationSpec s;
        s.id = "slide";
        s.ease = Ease::SlideIn;
        s.durationMs = 5000;
        ctl.start(s, 0);
        assert(near(ctl.value("slide", 1), 1.0));  // instant end state
        assert(ctl.update(1).size() == 1);
        assert(ctl.reducedMotion());
    }
    // --- performance mode halves durations; speed scales time ---
    {
        AnimationController ctl;
        ctl.configure(false, true, 1.0);
        AnimationSpec s;
        s.id = "p";
        s.durationMs = 200;
        assert(ctl.effectiveDurationMs(s) == 100);
        ctl.start(s, 0);
        assert(ctl.update(100).size() == 1);
        AnimationController fast;
        fast.configure(false, false, 2.0);
        AnimationSpec f;
        f.id = "f";
        f.durationMs = 200;
        fast.start(f, 0);
        assert(fast.update(100).size() == 1);  // 2x speed
        fast.cancelAll();
        assert(fast.running() == 0);
    }

    std::cout << "test_animation: PASS\n";
    return 0;
}
