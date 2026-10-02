#include "xykell/input_router.h"

#include <cmath>

namespace xykell::input {

Route InputRouter::onTouch(const TouchPoint& t, gui::GuiController& gui,
                            ModuleManager& mods) {
    if (!guiOpen_) {
        return Route::ToGame;
    }
    if (t.action == 0) { // down
        tracking_ = true;
        moved_ = false;
        lastY_ = t.y;
        // Back region: tap in header closes the GUI.
        if (t.y < headerHeight) {
            gui.open = false;
            guiOpen_ = false;
            tracking_ = false;
            return Route::ToGui;
        }
        return Route::ToGui;
    }
    if (t.action == 2 && tracking_) { // move: vertical scroll
        const float dy = t.y - lastY_;
        lastY_ = t.y;
        if (std::fabs(dy) > 2.0f) {
            moved_ = true;
        }
        scroll_ -= dy;
        if (scroll_ < 0.0f) {
            scroll_ = 0.0f;
        }
        const float contentH =
            static_cast<float>(gui.visible().size()) * rowHeight + headerHeight;
        const float maxScroll = (viewH_ > 0.0f && contentH > viewH_) ? contentH - viewH_ : 0.0f;
        if (scroll_ > maxScroll) {
            scroll_ = maxScroll;
        }
        return Route::ToGui;
    }
    if (t.action == 1 && tracking_) { // up: tap selects (no drag happened)
        tracking_ = false;
        if (!moved_ && t.y >= headerHeight && rowHeight > 0.0f) {
            const int index =
                static_cast<int>((t.y - headerHeight + scroll_) / rowHeight);
            const auto vis = gui.visible();
            if (index >= 0 && index < static_cast<int>(vis.size())) {
                gui.requestToggle(vis[static_cast<std::size_t>(index)].id, mods);
            }
        }
        moved_ = false;
        return Route::ToGui;
    }
    return Route::ToGui;
}

} // namespace xykell::input
