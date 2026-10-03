// Host unit test: HUD layout serialize/round-trip, editor, unavailable rule.
#include <cassert>
#include <iostream>

#include "xykell/hud_model.h"
#include "xykell/theme.h"

int main() {
    using namespace xykell;
    // Unverified source renders "--", never fabricated data.
    hud::HudElement fps;
    fps.type = hud::ElementType::Fps;
    assert(fps.text() == hud::kUnavailable);
    fps.provider = [] { return std::string("60"); };
    assert(fps.text() == "60");
    hud::HudElement throwing;
    throwing.provider = []() -> std::string { throw 1; };
    assert(throwing.text() == hud::kUnavailable);

    // Layout round-trip.
    hud::HudManager mgr;
    assert(mgr.layout().elements.size() == 4);
    std::string err;
    const std::string blob = json::stringify(mgr.saveLayout());
    hud::HudManager mgr2;
    assert(mgr2.loadLayout(json::parse(blob).value, err));
    assert(mgr2.layout().elements.size() == 4);
    assert(mgr2.layout().elements[0].type == hud::ElementType::Watermark);

    // Corrupt layout rejected, current kept.
    assert(!mgr2.loadLayout(json::parse(R"({"elements":[{"type":"nope"}]})").value, err));
    assert(mgr2.layout().elements.size() == 4);

    // Editor: select/move/apply + reset.
    auto& ed = mgr2.editor();
    assert(!ed.apply(mgr2.layout())); // nothing selected
    ed.select(1);
    ed.dx = 10.0f;
    ed.dy = -5.0f;
    assert(ed.apply(mgr2.layout()));
    assert(mgr2.layout().elements[1].x == 26.0f);
    ed.reset(mgr2.layout(), hud::HudLayout::m1Default());
    assert(mgr2.layout().elements[1].x == 16.0f);

    // Themes: builtins + serialization + bad color rejected.
    ui::Theme t;
    assert(ui::ThemeManager::find("Xykell Dark", t));
    assert(t.accent == "#4FD8C7");
    ui::Theme t2;
    assert(!ui::ThemeManager::find("Nope", t2));
    const std::string tblob = json::stringify(t.serialize());
    ui::Theme t3;
    assert(t3.deserialize(json::parse(tblob).value, err));
    assert(t3.accent == "#4FD8C7" && t3.name == "Xykell Dark");
    ui::Theme t4;
    assert(!t4.deserialize(json::parse(R"({"accent":"red"})").value, err));

    // Batch 9: seven builtins incl. Midnight/Aurora/Crimson, new semantic
    // tokens present on every builtin, old JSON still parses (new keys
    // default), round-trip preserves the new tokens.
    {
        const auto& all = ui::ThemeManager::builtins();
        assert(all.size() == 7);
        for (const char* n :
             {"Xykell Dark", "Xykell Midnight", "Xykell Aurora", "Xykell Crimson",
              "Xykell Minimal"}) {
            ui::Theme f;
            assert(ui::ThemeManager::find(n, f));
            assert(!f.elevated.empty() && !f.border.empty() && !f.hudAccent.empty());
        }
        ui::Theme aur;
        assert(ui::ThemeManager::find("Xykell Aurora", aur));
        assert(aur.accent == "#3FE0A8" && aur.hudAccent == "#7C6CF0");
        ui::Theme old;
        assert(old.deserialize(json::parse(R"({"name":"Legacy"})").value, err));
        assert(old.elevated == ui::Theme().elevated);  // missing keys: defaults
        ui::Theme rt;
        assert(rt.deserialize(json::parse(json::stringify(aur.serialize())).value, err));
        assert(rt.elevated == aur.elevated && rt.border == aur.border
               && rt.hudAccent == aur.hudAccent);
    }

    std::cout << "test_hud_theme: PASS\n";
    return 0;
}
