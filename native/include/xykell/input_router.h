#pragma once

// Touch routing for ClickGUI. Geometry is abstract (row list under a header)
// with a host-provided viewport; without one, raw-pixel defaults apply and
// are documented. Closed GUI -> ToGame (caller keeps M1 behavior). Open GUI
// -> taps/scroll consumed by the GUI, never leaked to gameplay.
#include "xykell/gui_controller.h"

namespace xykell::input {

struct TouchPoint {
    float x = 0.0f;
    float y = 0.0f;
    int action = 0; // 0=down, 1=up, 2=move
};

enum class Route { ToGame, ToGui };

class InputRouter {
  public:
    float rowHeight = 96.0f; // documented default; host may override
    float headerHeight = 64.0f;

    void setGuiOpen(bool open) { guiOpen_ = open; }
    bool guiOpen() const { return guiOpen_; }
    void setViewport(float w, float h) {
        viewW_ = w;
        viewH_ = h;
    }
    float scroll() const { return scroll_; }

    // Returns route; may mutate controller (selection, scroll, open flag)
    // and manager (toggles) via GuiController::requestToggle.
    Route onTouch(const TouchPoint& t, gui::GuiController& gui, ModuleManager& mods);

  private:
    bool guiOpen_ = false;
    float viewW_ = 0.0f;
    float viewH_ = 0.0f;
    float scroll_ = 0.0f;
    float lastY_ = 0.0f;
    bool tracking_ = false;
    bool moved_ = false;
};

} // namespace xykell::input
