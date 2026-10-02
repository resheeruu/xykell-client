// ClickGUI host + recovery module. Verified ModMenu API only.
#include "xykell/hud.h"

#include <pl/ModMenu.hpp>

#include "xykell/crash_guard.h"
#include "xykell/file_util.h"
#include "xykell/module_manager.h"
#include "xykell/runtime_probe.h"
#include "xykell/version.h"

namespace xykell {

namespace {

CrashGuard* gGuard = nullptr;
ModuleManager* gMgr = nullptr;

} // namespace

void setRecoveryContext(CrashGuard* guard, ModuleManager* mods) {
    gGuard = guard;
    gMgr = mods;
}

bool registerClickGuiModule(const std::string& modId, const std::string& registryJson) {
    if (registryJson.empty()) {
        return false; // honest: no catalog, no GUI (menu + HUD still work)
    }
    std::string err;
    if (!clickGui().loadRegistry(registryJson, err)) {
        return false;
    }
    return pl::modmenu::ModuleBuilder(kClickGuiModuleId, "Xykell ClickGUI")
        .description("Touch module browser. RESEARCH_REQUIRED entries are display-only.")
        .modId(modId)
        .defaultEnabled(false)
        .onToggle([](std::string_view id, bool enabled) {
            (void)id;
            clickGui().open = enabled;
            inputRouter().setGuiOpen(enabled);
        })
        .registerModule();
}

void unregisterClickGuiModule() {
    clickGui().open = false;
    inputRouter().setGuiOpen(false);
    pl::modmenu::unregisterModule(kClickGuiModuleId);
}

bool registerRecoveryModule(const std::string& modId, const std::string& report) {
    return pl::modmenu::ModuleBuilder(kRecoveryModuleId, "Xykell Recovery")
        .description(report)
        .modId(modId)
        .defaultEnabled(true)
        .config("clear_quarantine", "Clear quarantine", pl::modmenu::ConfigType::Toggle,
                "false")
        .onConfigChanged([](std::string_view id, std::string_view key,
                            std::string_view value) {
            (void)id;
            // Explicit user action only; never automatic.
            if (key == "clear_quarantine" && value == "true" && gGuard != nullptr) {
                for (const auto& q : gGuard->quarantined()) {
                    gGuard->clearQuarantine(q);
                    if (gMgr != nullptr) {
                        gMgr->setEnabled(q, false); // back to Loaded, NOT enabled
                    }
                }
                std::string err;
                gGuard->exitSafeMode();
                gGuard->save(err);
            }
        })
        .registerModule();
}

void unregisterRecoveryModule() { pl::modmenu::unregisterModule(kRecoveryModuleId); }

bool registerDiagnosticsModule(const std::string& modId) {
    const auto probe = runtime::RuntimeProbe::collect(XYKELL_VERSION,
                                                      XYKELL_LEVI_PIN, XYKELL_PRELOADER_PIN);
    return pl::modmenu::ModuleBuilder("xykell-diagnostics", "Xykell Runtime Diagnostics")
        .description(runtime::formatProbeReport(probe))
        .modId(modId)
        .defaultEnabled(true)
        .registerModule();
}

void unregisterDiagnosticsModule() {
    pl::modmenu::unregisterModule("xykell-diagnostics");
}

} // namespace xykell
