// Host unit test: renderer placeholders/theme/clamp + profile layout IO.
#include <algorithm>
#include <cassert>
#include <iostream>
#include <vector>

#include "xykell/hud_renderer.h"
#include "xykell/notifications.h"
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

    // --- hud.arraylist: the real list, deterministic, bounded.
    {
        ModuleManager m;
        m.registerModule({"z_mod", "Zebra", "hud"});
        m.registerModule({"a_mod", "Apple", "client"});
        m.registerModule({"b_mod", "Bee", "hud"});
        m.setEnabled("a_mod", true);
        m.setEnabled("b_mod", true);
        m.setEnabled("z_mod", true);

        // category, then id -> Apple(client), Bee(hud), Zebra(hud)
        const auto names = hud::enabledModuleNames(m);
        assert((names == std::vector<std::string>{"Apple", "Bee", "Zebra"}));

        // Deterministic across calls, not registration order.
        assert(hud::enabledModuleNames(m) == names);

        // Disabled modules never appear.
        m.setEnabled("b_mod", false);
        const auto after = hud::enabledModuleNames(m);
        assert(after.size() == 2);
        assert(std::find(after.begin(), after.end(), "Bee") == after.end());

        // Quarantined modules never appear as active functionality.
        m.quarantine("a_mod", "boom");
        const auto quar = hud::enabledModuleNames(m);
        assert(quar.size() == 1 && quar[0] == "Zebra");

        // limit is honoured, keeping the first N in sorted order.
        ModuleManager limMods;
        limMods.registerModule({"z_mod", "Zebra", "hud"});
        limMods.registerModule({"a_mod", "Apple", "client"});
        limMods.registerModule({"b_mod", "Bee", "hud"});
        limMods.setEnabled("a_mod", true);
        limMods.setEnabled("b_mod", true);
        limMods.setEnabled("z_mod", true);
        const auto lim = hud::enabledModuleNames(limMods, 1);
        assert(lim.size() == 1 && lim[0] == "Apple");
        // limit 0 means "no limit", not "none".
        assert(hud::enabledModuleNames(limMods, 0).size() == 3);

        // An empty set is empty, not a placeholder.
        ModuleManager none;
        assert(hud::enabledModuleNames(none).empty());
    }

    // --- hud.notifications: bound to a line, non-draining, severity-marked.
    {
        ui::NotificationCenter c;
        c.post("plain info", ui::NotifyPriority::Info, 3000, 1000);
        c.post("warned", ui::NotifyPriority::Warning, 3000, 1001);
        c.post("broke", ui::NotifyPriority::Error, 3000, 1002);
        const auto lines = hud::notificationLines(c, 2);
        assert(lines.size() == 2);                 // newest kept
        assert(lines[0] == "[*] warned");          // severity marked
        assert(lines[1] == "[!] broke");
        // Rendering must not consume: a hidden HUD would eat notifications.
        assert(c.pending() == 3);
        assert(hud::notificationLines(c, 2) == lines);

        // limit <= 0 draws nothing rather than everything.
        assert(hud::notificationLines(c, 0).empty());
        // Empty queue -> empty, and the caller shows the unknown marker.
        ui::NotificationCenter empty;
        assert(hud::notificationLines(empty, 3).empty());

        // Bounded: overflow drops oldest, queue never exceeds kCap.
        ui::NotificationCenter big;
        for (int i = 0; i < (int)ui::NotificationCenter::kCap + 10; ++i) {
            big.post("n" + std::to_string(i), ui::NotifyPriority::Info, 100, (std::uint64_t)i);
        }
        assert(big.pending() == ui::NotificationCenter::kCap);
        assert(big.peek().front().text == "n10");

        // Expiry drops stale entries oldest-first.
        assert(big.expireOlderThan(5, 2) == 0);      // nowMs <= postedAt
        assert(big.expireOlderThan(13, 2) == 1);     // n10 is 3ms old
        assert(big.peek().front().text == "n11");
    }

    // --- both elements render real text, not a count or a placeholder.
    {
        hud::HudManager m2;
        auto layout = hud::HudLayout::m1Default();
        layout.elements.clear();
        hud::HudElement list;
        list.type = hud::ElementType::ModuleList;
        list.x = 16.0f;
        list.y = 40.0f;
        layout.elements.push_back(list);
        hud::HudElement note;
        note.type = hud::ElementType::Notifications;
        note.x = 16.0f;
        note.y = 64.0f;
        layout.elements.push_back(note);
        m2.layout() = layout;

        ModuleManager mm;
        mm.registerModule({"fps_mod", "FPS", "hud"});
        mm.setEnabled("fps_mod", true);
        ui::NotificationCenter nc;
        nc.post("saved", ui::NotifyPriority::Info, 1000, 10);

        hud::RenderContext c2;
        ui::Theme th2;
        assert(ui::ThemeManager::find("Xykell Dark", th2));
        c2.theme = &th2;
        c2.modules = &mm;
        c2.notifications = &nc;
        const auto out = hud::renderHud(m2, c2);
        // Layout elements first, then the version/taps/module-count footer.
        assert(out.size() >= 2);
        assert(out[0].text.find("FPS") != std::string::npos);
        assert(out[0].text.find('/') == std::string::npos); // a count, not a list
        assert(out[1].text == "saved");

        // No module manager -> explicit unknown, never a fabricated list.
        hud::RenderContext c3 = c2;
        c3.modules = nullptr;
        const auto unknown = hud::renderHud(m2, c3);
        assert(unknown[0].text.find(hud::kUnavailable) != std::string::npos);
    }

    std::cout << "test_hud_render: PASS\n";
    return 0;
}
