#pragma once

// HUD framework: manager + elements + layouts + editor state. Elements whose
// runtime data source is unverified render UNAVAILABLE ("--") — see
// HudElement::text(), which returns the placeholder unless a provider is set.
// Layouts serialize into the profile system (Profile::hudLayout).
#include <functional>
#include <string>
#include <vector>

#include "xykell/json_min.h"

namespace xykell::hud {

inline constexpr const char* kUnavailable = "--";

enum class ElementType {
    Watermark,
    Fps,
    Coordinates,
    Cps,
    ModuleList,
    Notifications,
    Armor,
    Health,
    Hunger,
    Keystrokes,
    TargetHud,
};

std::string typeName(ElementType t);
bool typeFromName(const std::string& name, ElementType& out);

struct HudElement {
    ElementType type = ElementType::Watermark;
    float x = 16.0f;
    float y = 48.0f;
    float scale = 1.0f;
    bool visible = true;
    std::string align = "left"; // left|center|right

    // Data provider installed when a verified source exists. Empty provider
    // (default) renders kUnavailable — never fabricated data.
    std::function<std::string()> provider;

    std::string text() const;
    json::Value serialize() const;
    bool deserialize(const json::Value& v, std::string& error);
};

struct HudLayout {
    std::string name = "Default";
    std::vector<HudElement> elements;

    static HudLayout m1Default(); // the four M1 proof lines as elements
    json::Value serialize() const;
    bool deserialize(const json::Value& v, std::string& error);
};

// Editor state (touch): selection + pending transform, applied on commit.
struct HudEditor {
    int selected = -1; // index into layout.elements, -1 = none
    float dx = 0.0f;
    float dy = 0.0f;
    float scale = 1.0f;
    bool visible = true;

    void select(int index) { selected = index; }
    void clear() { selected = -1; }
    bool apply(HudLayout& layout); // false when nothing selected
    void reset(HudLayout& layout, const HudLayout& defaults);
};

class HudManager {
  public:
    HudManager() : layout_(HudLayout::m1Default()) {}

    HudLayout& layout() { return layout_; }
    const HudLayout& layout() const { return layout_; }
    HudEditor& editor() { return editor_; }
    bool loadLayout(const json::Value& v, std::string& error);
    json::Value saveLayout() const { return layout_.serialize(); }
    void resetToDefaults() { layout_ = HudLayout::m1Default(); }

  private:
    HudLayout layout_;
    HudEditor editor_;
};

} // namespace xykell::hud
