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
    // "LOW" alert while the observed health (SetHealth 0x2A) is at or under a
    // threshold. Renders kUnavailable when health has never been observed, so
    // an unknown value never reads as an alarm.
    LowHealth,
    // Observed entity count from the relay's entity table, and the server's
    // own tick rate derived from two SetTime samples. Both render
    // kUnavailable before enough has been observed.
    EntityCounter,
    Tps,
    // Who is online, from the PlayerList 0x3f entries the relay has observed.
    // Renders kUnavailable until the server has actually sent a roster --
    // an empty list is not the same claim as "nobody is here".
    TabList,
    // Where the session is connected. Both read the relay's own handshake, so
    // they are facts rather than a guess about the world: the host the user
    // configured and the protocol the client announced.
    ServerInfo,
    IpDisplay,
    Hunger,
    Keystrokes,
    TargetHud,
    // App/device facts (cores, memory, storage, ABI). Data is supplied by a
    // provider that read real measurements; with no provider it renders
    // kUnavailable, so it never shows invented numbers.
    HardwareStats,
    // Motion readouts fed from observed travel (Stage 12 snapshot):
    // 8-point compass from yaw, meters-per-second between observations.
    // Unbound/empty feed renders kUnavailable — never invented motion.
    Direction,
    SpeedMeter,
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
    // Grid-snapped commit: rounds the pending transform to the grid before
    // applying (grid <= 0 disables snapping). Touch drags feed dx/dy.
    bool applySnapped(HudLayout& layout, float grid);
    void reset(HudLayout& layout, const HudLayout& defaults);
};

// Snap a coordinate to a grid (grid <= 0: unchanged). Pure math for the
// touch editor; host-tested, no platform involved.
inline float snapToGrid(float v, float grid) {
    if (grid <= 0.0f) {
        return v;
    }
    // Round-half-away without <cmath> dependency surprises on NDK builds.
    const float q = v / grid;
    const long n = static_cast<long>(q >= 0.0f ? q + 0.5f : q - 0.5f);
    return static_cast<float>(n) * grid;
}

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
