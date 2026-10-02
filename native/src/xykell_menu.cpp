// Task 4: Mod Menu proof via the Xykell portal (preloader backend).
// No pl/ includes here — the backend owns the SDK dependency.
#include "xykell/menu.h"

#include <string_view>

#include "xykell/core.h"
#include "xykell/portal.h"
#include "xykell/version.h"

namespace xykell {

namespace {

void logInfo(const std::string& text) {
    if (portal::backend().logger != nullptr) {
        portal::backend().logger->info(text);
    }
}

bool parseToggle(std::string_view v) { return v == "true" || v == "1"; }

} // namespace

bool registerMenuModule(const std::string& modId) {
    auto* menu = portal::backend().menu;
    if (menu == nullptr) {
        return false;
    }
    portal::MenuModule m;
    m.moduleId = kMenuModuleId;
    m.displayName = "Xykell Core";
    m.description = "Xykell client core: lifecycle, version gate, M1 proofs.";
    m.modId = modId;
    m.defaultEnabled = true;
    // NOTE: lambdas must not capture locals; the preloader owns them
    // past load(). All state goes through the singleton.
    m.onToggle = [](std::string_view id, bool enabled) {
        XykellCore::instance().setModEnabled(enabled);
        logInfo(std::string("menu: ") + std::string(id) + " -> "
                + (enabled ? "ON" : "OFF"));
    };
    m.onConfigChanged = [](std::string_view id, std::string_view key,
                            std::string_view value) {
        auto& core = XykellCore::instance();
        const bool on = parseToggle(value);
        if (key == "safe_mode") {
            core.setSafeMode(on);
        } else if (key == "debug_logging") {
            core.setDebugLogging(on);
        } else if (key == "enabled") {
            core.setModEnabled(on);
        }
        logInfo(std::string("menu: ") + std::string(id) + " " + std::string(key)
                + "=" + std::string(value));
    };
    auto cfg = [](const char* k, const char* n, const char* d) {
        portal::ConfigEntry e;
        e.key = k;
        e.displayName = n;
        e.kind = portal::ConfigKind::Toggle;
        e.defaultValue = d;
        return e;
    };
    m.configs = {cfg("enabled", "Enabled", "true"),
                 cfg("safe_mode", "Safe Mode", "false"),
                 cfg("debug_logging", "Debug Logging", "false")};
    const bool ok = menu->registerModule(m);
    XykellCore::instance().addCapability(Capability::Config);
    return ok;
}

void unregisterMenuModule() {
    if (portal::backend().menu != nullptr) {
        portal::backend().menu->unregisterModule(kMenuModuleId);
    }
}

} // namespace xykell
