// Host unit test: local-data HUD providers + first real modules (Batch 1).
// Deterministic injected time only; no platform, no game, no I/O.
#include <cassert>
#include <iostream>
#include <string>

#include "xykell/hud_sources.h"

using namespace xykell;
using namespace xykell::hud;
using namespace xykell::hud::sources;

// Counts "label value" pairs separated by the two-space delimiter.
static int fieldsIn(const std::string& s) {
    int n = 0;
    for (std::size_t i = 0; i + 1 < s.size(); ++i) {
        if (s[i] == ' ' && s[i + 1] == ' ') {
            ++n;
        }
    }
    return n + 1;
}

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

    // --- HardwareStats: deterministic, bounded, never fabricated ---
    {
        // Nothing measured: no line at all, never zeros.
        assert(formatHardwareStats(HardwareStats()) == kUnavailable);

        HardwareStats s;
        s.cpuCores = 8;
        s.javaHeapUsedMB = 128;
        s.systemAvailableMB = 2048;
        s.systemTotalMB = 8192;
        s.storageFreeMB = 4096;
        s.abi = "arm64-v8a";
        assert(formatHardwareStats(s) ==
               "CPU 8c  Heap 128MB  MEM 2048/8192MB  Store 4096MB  ABI arm64");

        // Field order is fixed regardless of assignment order.
        HardwareStats r;
        r.abi = "armeabi-v7a";
        r.cpuCores = 2;
        assert(formatHardwareStats(r) == "CPU 2c  ABI armeabi-v7a");  // only -v8a shortens

        // Unknown fields are omitted, never printed as 0.
        HardwareStats partial;
        partial.cpuCores = 4;
        assert(formatHardwareStats(partial) == "CPU 4c");

        // Low memory is visible in the label, not hidden.
        HardwareStats low = partial;
        low.systemLowMemory = true;
        low.systemAvailableMB = 96;
        low.systemTotalMB = 4096;
        assert(formatHardwareStats(low) == "CPU 4c  MEM! 96/4096MB");

        // Total-only (available unknown) is still reported.
        HardwareStats tot;
        tot.systemTotalMB = 2048;
        assert(formatHardwareStats(tot) == "MEM /2048MB");

        // ABI shortening: -v8a suffix stripped, other names untouched.
        assert(shortAbi("arm64-v8a") == "arm64");
        assert(shortAbi("x86_64") == "x86_64");
        assert(shortAbi("armeabi-v7a") == "armeabi-v7a");
        assert(shortAbi("") == "");

        // Bounded output: no field count or length can run away.
        HardwareStats big;
        big.cpuCores = 16;
        big.javaHeapUsedMB = 999999;
        big.systemAvailableMB = 999999;
        big.systemTotalMB = 999999;
        big.storageFreeMB = 999999;
        big.abi = "arm64-v8a";
        const std::string bigText = formatHardwareStats(big);
        assert(bigText.size() <= kHardwareStatsMaxText);
        assert(fieldsIn(bigText) <= kHardwareStatsMaxFields);
    }
    // --- HardwareStats provider binding: unmeasured => unavailable ---
    {
        HudLayout layout;
        HudElement el;
        el.type = ElementType::HardwareStats;
        layout.elements.push_back(el);
        HudElement other;
        other.type = ElementType::Watermark;
        layout.elements.push_back(other);

        // No provider at all: the element itself renders unavailable.
        assert(layout.elements[0].text() == kUnavailable);

        bindHardwareStatsProvider(layout, HardwareStats());
        // An empty source still renders unavailable, not a zeroed line.
        assert(layout.elements[0].text() == kUnavailable);

        HardwareStats s;
        s.cpuCores = 6;
        bindHardwareStatsProvider(layout, s);
        assert(layout.elements[0].text() == "CPU 6c");
        // Binding must not touch other element types.
        assert(layout.elements[1].text() == kUnavailable);
    }
    // --- Element type round-trips through its persisted name ---
    {
        ElementType t;
        assert(typeFromName("hardware_stats", t));
        assert(t == ElementType::HardwareStats);
        assert(typeName(ElementType::HardwareStats) == "hardware_stats");
    }

    std::cout << "test_hud_sources: PASS\n";
    return 0;
}
