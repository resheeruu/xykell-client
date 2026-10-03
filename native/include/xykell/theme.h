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
    std::string elevated = "#1E2A45";   // raised cards/dialogs
    std::string accent = "#4FD8C7";
    std::string text = "#E8EEF7";       // primary text
    std::string muted = "#8A97AD";      // secondary text
    std::string border = "#2A3A58";
    std::string hudAccent = "#4FD8C7";  // HUD-tinted accents
    std::string warning = "#E8B34B";
    std::string error = "#E05D5D";
    std::string success = "#5DD39E";
    // module-state tints
    std::string supported = "#5DD39E";
    std::string partial = "#E8B34B";
    std::string unavailable = "#8A97AD";
    // surface styling
    double opacity = 1.0;
    double radius = 8.0;

    json::Value serialize() const;
    bool deserialize(const json::Value& v, std::string& error);
};

class ThemeManager {
  public:
    static const std::vector<Theme>& builtins();
    static bool find(const std::string& name, Theme& out);
};

} // namespace xykell::ui
