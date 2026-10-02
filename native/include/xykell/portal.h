#pragma once

// Xykell runtime portal: Xykell-owned seams for menu/overlay/input/logging.
// Production code talks ONLY to these interfaces; backends provide the
// mechanism. Current backend: Levi preloader ("LEGACY COMPATIBILITY MODE",
// see portal_preloader.cpp). A future standalone loader backend implements
// the same interfaces — no call-site changes. Pure C++ (host-testable).
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace xykell::portal {

struct Logger {
    virtual ~Logger() = default;
    virtual void info(const std::string& msg) = 0;
    virtual void warn(const std::string& msg) = 0;
    virtual void error(const std::string& msg) = 0;
};

enum class ConfigKind { Toggle, SliderInt, SliderFloat, Text, Color, Keybind };

struct ConfigEntry {
    std::string key;
    std::string displayName;
    ConfigKind kind = ConfigKind::Toggle;
    std::string defaultValue;
    std::string minValue;
    std::string maxValue;
};

using ToggleCallback = std::function<void(const std::string& moduleId, bool enabled)>;
using ConfigChangedCallback =
    std::function<void(const std::string& moduleId, const std::string& key,
                       const std::string& value)>;

struct MenuModule {
    std::string moduleId;
    std::string displayName;
    std::string description;
    std::string modId;
    bool defaultEnabled = false;
    std::vector<ConfigEntry> configs;
    ToggleCallback onToggle;
    ConfigChangedCallback onConfigChanged;
};

struct MenuRegistry {
    virtual ~MenuRegistry() = default;
    virtual bool registerModule(const MenuModule& m) = 0;
    virtual void unregisterModule(const std::string& moduleId) = 0;
    virtual void setModuleEnabled(const std::string& moduleId, bool enabled) = 0;
};

struct OverlayLine {
    std::string text;
    float x = 16.0f, y = 48.0f, size = 20.0f;
    std::uint32_t color = 0xFFFFFFFF;
};

struct Overlay {
    virtual ~Overlay() = default;
    // Empty lines = clear. Implementations must never throw.
    virtual void submit(const std::string& moduleId,
                        const std::vector<OverlayLine>& lines) = 0;
};

struct TouchPoint {
    float x = 0.0f, y = 0.0f;
    int action = 0; // 0=down, 1=up, 2=move
};

// Return true when consumed. No unregister in the underlying API —
// enable/disable is enforced by the subscriber via core flags.
using TouchCallback = std::function<bool(const TouchPoint&)>;

struct Input {
    virtual ~Input() = default;
    virtual void onTouch(TouchCallback cb) = 0;
};

// Process-wide backend set once at load. Never null after init.
struct Backend {
    Logger* logger = nullptr;
    MenuRegistry* menu = nullptr;
    Overlay* overlay = nullptr;
    Input* input = nullptr;
};

void setBackend(const Backend& b);
const Backend& backend();

} // namespace xykell::portal
