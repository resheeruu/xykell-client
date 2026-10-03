// Host unit test: HUD editor snapping + commit flows (Batch 8).
#include <cassert>
#include <iostream>

#include "xykell/hud_model.h"

using namespace xykell::hud;

int main() {
    // --- snap math ---
    {
        assert(snapToGrid(7.0f, 0.0f) == 7.0f);   // grid off: unchanged
        assert(snapToGrid(-3.0f, -1.0f) == -3.0f);
        assert(snapToGrid(7.0f, 8.0f) == 8.0f);
        assert(snapToGrid(11.9f, 8.0f) == 8.0f);
        assert(snapToGrid(12.0f, 8.0f) == 16.0f);  // half rounds away
        assert(snapToGrid(-12.0f, 8.0f) == -16.0f);
    }
    // --- snapped commit on a real layout ---
    {
        HudLayout layout = HudLayout::m1Default();
        assert(!layout.elements.empty());
        HudEditor ed;
        assert(!ed.applySnapped(layout, 8.0f));  // nothing selected
        ed.select(0);
        const float x0 = layout.elements[0].x;
        const float y0 = layout.elements[0].y;
        ed.dx = 11.9f;
        ed.dy = 4.1f;
        ed.scale = 1.5f;
        ed.visible = false;
        assert(ed.applySnapped(layout, 8.0f));
        assert(layout.elements[0].x == x0 + 8.0f);  // 11.9 snapped to 8
        assert(layout.elements[0].y == y0 + 8.0f);  // 4.1 snapped to 8
        assert(layout.elements[0].scale == 1.5f);
        assert(!layout.elements[0].visible);
        // Serialize round-trips the snapped layout.
        std::string err;
        HudLayout re;
        assert(re.deserialize(layout.serialize(), err));
        assert(re.elements[0].x == layout.elements[0].x);
    }

    std::cout << "test_hud_editor: PASS\n";
    return 0;
}
