// Host unit test: renderer placeholders/theme/clamp + profile layout IO.
#include <cassert>
#include <iostream>

#include "xykell/hud_renderer.h"
#include "xykell/profile_manager.h"

int main() {
    using namespace xykell;
    // Theme colors: #RRGGBB -> opaque ARGB; garbage -> white fallback.
    assert(hud::themeColor("#4FD8C7") == 0xFF4FD8C7);
    assert(hud::themeColor("#804FD8C7") == 0x804FD8C7);
    assert(hud::themeColor("red") == 0xFFFFFFFF);
    assert(hud::themeColor("#ZZZZZZ") == 0xFFFFFFFF);
    assert(hud::themeColor("") == 0xFFFFFFFF);

    hud::HudManager mgr;
    ui::Theme theme;
    assert(ui::ThemeManager::find("Xykell Dark", theme));
    ModuleManager mods;
    mods.registerModule({"a", "A", "client"});
    mods.setEnabled("a", true);
    mods.registerModule({"b", "B", "hud"});

    hud::RenderContext ctx;
    ctx.theme = &theme;
    ctx.modules = &mods;
    ctx.taps = 7;
    ctx.versionLine = "XYKELL TEST";
    const auto lines = hud::renderHud(mgr, ctx);
    assert(lines.size() == 4 + 3); // 4 elements + version + taps + modules
    bool sawFpsPh = false, sawTaps = false, sawMods = false, sawAccent = false;
    for (const auto& l : lines) {
        if (l.text == "FPS: --") {
            sawFpsPh = true;
        }
        if (l.text == "taps: 7") {
            sawTaps = true;
        }
        if (l.text == "modules: 1/2 on") {
            sawMods = true;
        }
        if (l.color == hud::themeColor("#4FD8C7")) {
            sawAccent = true; // watermark uses theme accent
        }
    }
    assert(sawFpsPh && sawTaps && sawMods && sawAccent);

    // Clamp: known viewport clamps; unknown viewport passes through.
    hud::HudLayout lay = hud::HudLayout::m1Default();
    lay.elements[0].x = 5000.0f;
    lay.elements[0].y = -10.0f;
    hud::clampToViewport(lay, 0.0f, 0.0f);
    assert(lay.elements[0].x == 5000.0f);
    hud::clampToViewport(lay, 1080.0f, 1920.0f);
    assert(lay.elements[0].x == 1080.0f && lay.elements[0].y == 0.0f);

    // Profile round-trip carries the layout.
    Profile p;
    hud::saveHudToProfile(p, mgr);
    hud::HudManager mgr2;
    std::string err;
    assert(hud::loadHudFromProfile(p, mgr2, err));
    assert(mgr2.layout().elements.size() == mgr.layout().elements.size());
    Profile bad;
    bad.hudLayout = json::Value(42); // corrupt: rejected, manager keeps layout
    assert(!hud::loadHudFromProfile(bad, mgr2, err));
    assert(mgr2.layout().elements.size() == mgr.layout().elements.size());

    std::cout << "test_hud_render: PASS\n";
    return 0;
}
