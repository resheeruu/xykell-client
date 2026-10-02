// ClickGUI host + recovery module via the Xykell portal.
#include "xykell/hud.h"

#include <string_view>

#include "xykell/crash_guard.h"
#include "xykell/file_util.h"
#include "xykell/module_manager.h"
#include "xykell/portal.h"
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
    auto* menu = portal::backend().menu;
    if (menu == nullptr) {
        return false;
    }
    portal::MenuModule m;
    m.moduleId = kClickGuiModuleId;
    m.displayName = "Xykell ClickGUI";
    m.description = "Touch module browser. RESEARCH_REQUIRED entries are display-only.";
    m.modId = modId;
    m.defaultEnabled = false;
    m.onToggle = [](std::string_view id, bool enabled) {
        (void)id;
        clickGui().open = enabled;
        inputRouter().setGuiOpen(enabled);
    };
    return menu->registerModule(m);
}

void unregisterClickGuiModule() {
    clickGui().open = false;
    inputRouter().setGuiOpen(false);
    if (portal::backend().menu != nullptr) {
        portal::backend().menu->unregisterModule(kClickGuiModuleId);
    }
}

bool registerRecoveryModule(const std::string& modId, const std::string& report) {
    auto* menu = portal::backend().menu;
    if (menu == nullptr) {
        return false;
    }
    portal::MenuModule m;
    m.moduleId = kRecoveryModuleId;
    m.displayName = "Xykell Recovery";
    m.description = report;
    m.modId = modId;
    m.defaultEnabled = true;
    portal::ConfigEntry clear;
    clear.key = "clear_quarantine";
    clear.displayName = "Clear quarantine";
    clear.kind = portal::ConfigKind::Toggle;
    clear.defaultValue = "false";
    m.configs = {clear};
    m.onConfigChanged = [](std::string_view id, std::string_view key,
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
    };
    return menu->registerModule(m);
}

void unregisterRecoveryModule() {
    if (portal::backend().menu != nullptr) {
        portal::backend().menu->unregisterModule(kRecoveryModuleId);
    }
}

bool registerDiagnosticsModule(const std::string& modId) {
    auto* menu = portal::backend().menu;
    if (menu == nullptr) {
        return false;
    }
    const auto probe = runtime::RuntimeProbe::collect(XYKELL_VERSION,
                                                      XYKELL_LEVI_PIN, XYKELL_PRELOADER_PIN);
    portal::MenuModule m;
    m.moduleId = "xykell-diagnostics";
    m.displayName = "Xykell Runtime Diagnostics";
    m.description = runtime::formatProbeReport(probe);
    m.modId = modId;
    m.defaultEnabled = true;
    return menu->registerModule(m);
}

void unregisterDiagnosticsModule() {
    if (portal::backend().menu != nullptr) {
        portal::backend().menu->unregisterModule("xykell-diagnostics");
    }
}

} // namespace xykell
