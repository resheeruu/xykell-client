#pragma once

// Theme abstraction: named color sets for ClickGUI/HUD/launcher.
// Two built-ins, no external assets. Serializes into config/profile JSON.
#include <string>
#include <vector>

#include "xykell/json_min.h"

namespace xykell::ui {

struct Theme {
    std::string name = "Xykell Dark";
    std::string background = "#0D1526";
    std::string surface = "#16213A";
    std::string accent = "#4FD8C7";
    std::string text = "#E8EEF7";
    std::string muted = "#8A97AD";
    std::string warning = "#E8B34B";
    std::string error = "#E05D5D";
    std::string success = "#5DD39E";
    // module-state tints
    std::string supported = "#5DD39E";
    std::string partial = "#E8B34B";
    std::string unavailable = "#8A97AD";

    json::Value serialize() const;
    bool deserialize(const json::Value& v, std::string& error);
};

class ThemeManager {
  public:
    static const std::vector<Theme>& builtins();
    static bool find(const std::string& name, Theme& out);
};

} // namespace xykell::ui
