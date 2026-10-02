// Preloader backend for the Xykell portal.
//
// *** LEGACY COMPATIBILITY MODE ***
// This backend talks to Levi preloader 0.2.3 (Apache-2.0, vendored headers +
// CI-linked runtime). It exists so Xykell runs TODAY. The standalone loader
// backend will implement the same portal interfaces; call sites must not
// change when it lands.
#include <pl/Input.hpp>
#include <pl/Logger.hpp>
#include <pl/ModMenu.hpp>

#include <mutex>

#include "xykell/portal.h"
#include "xykell/portal_preloader.h"

namespace xykell::portal {
namespace {

::pl::modmenu::ConfigType toPl(ConfigKind k) {
    switch (k) {
        case ConfigKind::Toggle: return ::pl::modmenu::ConfigType::Toggle;
        case ConfigKind::SliderInt: return ::pl::modmenu::ConfigType::SliderInt;
        case ConfigKind::SliderFloat: return ::pl::modmenu::ConfigType::SliderFloat;
        case ConfigKind::Text: return ::pl::modmenu::ConfigType::Text;
        case ConfigKind::Color: return ::pl::modmenu::ConfigType::Color;
        case ConfigKind::Keybind: return ::pl::modmenu::ConfigType::Keybind;
    }
    return ::pl::modmenu::ConfigType::Toggle;
}

class PreloaderLogger : public Logger {
  public:
    explicit PreloaderLogger(::pl::log::Logger& inner) : inner_(inner) {}
    void info(const std::string& msg) override { inner_.info("{}", msg); }
    void warn(const std::string& msg) override { inner_.warn("{}", msg); }
    void error(const std::string& msg) override { inner_.error("{}", msg); }

  private:
    ::pl::log::Logger& inner_;
};

class PreloaderMenu : public MenuRegistry {
  public:
    bool registerModule(const MenuModule& m) override {
        // Adapt Xykell (const string&) callbacks to preloader (string_view).
        // Copies are owned by the lambda capture — safe past load().
        ToggleCallback onToggle = m.onToggle;
        ConfigChangedCallback onChanged = m.onConfigChanged;
        auto builder = ::pl::modmenu::ModuleBuilder(m.moduleId, m.displayName)
                           .description(m.description)
                           .modId(m.modId)
                           .defaultEnabled(m.defaultEnabled)
                           .onToggle([onToggle](std::string_view id, bool on) {
                               if (onToggle) {
                                   onToggle(std::string(id), on);
                               }
                           })
                           .onConfigChanged([onChanged](std::string_view id,
                                                       std::string_view key,
                                                       std::string_view value) {
                               if (onChanged) {
                                   onChanged(std::string(id), std::string(key),
                                             std::string(value));
                               }
                           });
        for (const auto& c : m.configs) {
            builder.config(c.key, c.displayName, toPl(c.kind), c.defaultValue,
                           c.minValue, c.maxValue);
        }
        return builder.registerModule();
    }
    void unregisterModule(const std::string& id) override {
        ::pl::modmenu::unregisterModule(id);
    }
    void setModuleEnabled(const std::string& id, bool on) override {
        ::pl::modmenu::setModuleEnabled(id, on);
    }
};

class PreloaderOverlay : public Overlay {
  public:
    void submit(const std::string& id, const std::vector<OverlayLine>& lines) override {
        std::vector<::pl::modmenu::DrawCommand> cmds;
        cmds.reserve(lines.size());
        for (const auto& l : lines) {
            ::pl::modmenu::DrawCommand c;
            c.type = ::pl::modmenu::DrawCommandType::Text;
            c.x = l.x;
            c.y = l.y;
            c.size = l.size;
            c.color = l.color;
            c.text = l.text;
            cmds.push_back(std::move(c));
        }
        ::pl::modmenu::submitDrawCommands(id, cmds);
    }
};

class PreloaderInput : public Input {  public:
    void onTouch(TouchCallback cb) override {
        // Callback kept alive by the preloader (process-wide, no unregister);
        // enable/disable enforced inside the subscriber via core flags.
        auto shared = std::make_shared<TouchCallback>(std::move(cb));
        ::pl::input::registerTouchCallback(
            [shared](const ::pl::input::TouchEvent& ev) {
                TouchPoint p{ev.x, ev.y, 0};
                if (ev.action == 2) {
                    p.action = 2;
                } else if (ev.action == 1) {
                    p.action = 1;
                }
                try {
                    return (*shared)(p);
                } catch (...) {
                    return false;
                }
            });
    }
};

} // namespace

Backend preloaderBackend(::pl::log::Logger& logger) {
    static PreloaderLogger log(logger);
    static PreloaderMenu menu;
    static PreloaderOverlay overlay;
    static PreloaderInput input;
    return Backend{&log, &menu, &overlay, &input};
}

} // namespace xykell::portal
