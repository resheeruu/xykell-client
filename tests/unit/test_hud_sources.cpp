// Host unit test: local-data HUD providers + first real modules (Batch 1).
// Deterministic injected time only; no platform, no game, no I/O.
#include <cassert>
#include <iostream>
#include <string>

#include "xykell/hud_sources.h"

using namespace xykell;
using namespace xykell::hud;
using namespace xykell::hud::sources;

int main() {
    // --- FrameTimer: exact window rate math ---
    {
        FrameTimer ft;
        assert(fpsText(ft, 1000) == kUnavailable);  // no data: no claim
        for (int i = 0; i < 60; ++i) {
            ft.push(1001 + i * 16);  // ~60 frames inside one second
        }
        assert(fpsText(ft, 2000) == "60");
        assert(fpsText(ft, 5000) == kUnavailable);  // window expired
        ft.push(6000);
        assert(fpsText(ft, 6000) == kUnavailable);  // single sample: no claim
        ft.push(6016);
        assert(fpsText(ft, 6016) == "2");
    }
    // --- TapCounter: CPS window, verified zero ---
    {
        TapCounter taps;
        assert(cpsText(taps, 1000) == "0");  // measured zero, not unknown
        for (int i = 0; i < 5; ++i) {
            taps.push(1000 + i * 100);
        }
        assert(cpsText(taps, 1500) == "5");
        assert(cpsText(taps, 3000) == "0");  // window expired
    }
    // --- Clock/session formatters ---
    {
        assert(formatClock(0) == "00:00");
        assert(formatClock(13 * 3600 + 5 * 60) == "13:05");
        assert(formatClock(86399) == "23:59");
        assert(formatSession(0) == "00:00");
        assert(formatSession(7000) == "00:07");
        assert(formatSession((1 * 3600 + 2 * 60 + 3) * 1000) == "1:02:03");
        assert(formatSession(-50) == "00:00");  // clamped, never negative
    }
    // --- bind: verified types get providers, others keep "--" ---
    {
        HudLayout layout;
        HudElement fps;
        fps.type = ElementType::Fps;
        HudElement coords;
        coords.type = ElementType::Coordinates;
        HudElement cps;
        cps.type = ElementType::Cps;
        layout.elements = {fps, coords, cps};
        FrameTimer ft;
        TapCounter taps;
        std::int64_t now = 10000;
        bindLocalProviders(layout, ft, taps, [&now]() { return now; });
        assert(layout.elements[1].text() == kUnavailable);  // no source: untouched
        for (int i = 0; i < 30; ++i) {
            ft.push(10000 + i * 33);
        }
        taps.push(10500);
        taps.push(10600);
        now = 11000;
        assert(layout.elements[0].text() == "29");  // frame at t=10000 aged out
        assert(layout.elements[2].text() == "2");
        assert(layout.elements[1].text() == kUnavailable);  // still no source
    }
    // --- first real modules: register/enable/quarantine-safe ---
    {
        ModuleManager mods;
        assert(registerLocalModules(mods) == 5);
        assert(registerLocalModules(mods) == 0);  // duplicates skipped safely
        assert(mods.setEnabled("xykell.hud.fps", true));
        assert(mods.setEnabled("xykell.hud.clock", true));
        assert(!mods.setEnabled("xykell.hud.keystrokes", true));  // not registered
        assert(mods.get("xykell.hud.cps")->state == ModuleState::Loaded);
        mods.quarantine("xykell.hud.fps", "test reason");
        assert(!mods.setEnabled("xykell.hud.fps", true));  // quarantined stays off
        assert(mods.list().size() == 5);
    }

    std::cout << "test_hud_sources: PASS\n";
    return 0;
}
