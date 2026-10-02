// Task 4: Mod Menu proof. Verified API only (pl/ModMenu.hpp @ preloader 0.2.3).
#include "xykell/menu.h"

#include <android/log.h>

#include <pl/ModMenu.hpp>

#include "xykell/core.h"
#include "xykell/version.h"

namespace xykell {

namespace {

void logInfo(const std::string& text) {
    __android_log_print(ANDROID_LOG_INFO, XYKELL_LOG_TAG, "%s", text.c_str());
}

bool parseToggle(std::string_view v) { return v == "true" || v == "1"; }

} // namespace

bool registerMenuModule(const std::string& modId) {
    const bool ok =
        pl::modmenu::ModuleBuilder(kMenuModuleId, "Xykell Core")
            .description("Xykell client core: lifecycle, version gate, M1 proofs.")
            .modId(modId)
            .defaultEnabled(true)
            // NOTE: lambdas must not capture locals; the preloader owns them
            // past load(). All state goes through the singleton.
            .onToggle([](std::string_view id, bool enabled) {
                XykellCore::instance().setModEnabled(enabled);
                logInfo(std::string("menu: ") + std::string(id) + " -> "
                        + (enabled ? "ON" : "OFF"));
            })
            .onConfigChanged([](std::string_view id, std::string_view key,
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
                logInfo(std::string("menu: ") + std::string(id) + " "
                        + std::string(key) + "=" + std::string(value));
            })
            .config("enabled", "Enabled", pl::modmenu::ConfigType::Toggle, "true")
            .config("safe_mode", "Safe Mode", pl::modmenu::ConfigType::Toggle, "false")
            .config("debug_logging", "Debug Logging", pl::modmenu::ConfigType::Toggle, "false")
            .registerModule();
    XykellCore::instance().addCapability(Capability::Config);
    return ok;
}

void unregisterMenuModule() { pl::modmenu::unregisterModule(kMenuModuleId); }

} // namespace xykell
