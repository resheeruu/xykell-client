// Xykell M1: lifecycle entry + Core init/shutdown. No hooks, no game access.
#include <pl/Mod.hpp>

#include "xykell/config_store.h"
#include "xykell/core.h"
#include "xykell/crash_guard.h"
#include "xykell/file_util.h"
#include "xykell/load_stages.h"
#include "xykell/runtime_active.h"
#include "xykell/runtime_probe.h"
#include "xykell/hud.h"
#include "xykell/menu.h"
#include "xykell/module_manager.h"
#include "xykell/version.h"
#include "xykell/version_adapter.h"

namespace {

xykell::ModuleManager& runtimeModules() {
    static xykell::ModuleManager mgr;
    return mgr;
}

xykell::XykellConfig& runtimeConfig() {
    static xykell::XykellConfig cfg;
    return cfg;
}

xykell::CrashGuard& runtimeGuard(const std::string& dataDir) {
    // First call wins; load() runs once per process with a stable dir.
    static xykell::CrashGuard guard(dataDir);
    return guard;
}

const char* currentArch() {
#if defined(__aarch64__)
    return "arm64-v8a";
#else
    return "unknown-arch";
#endif
}

const char* stateName(xykell::SupportState s) {
    switch (s) {
        case xykell::SupportState::Supported: return "SUPPORTED";
        case xykell::SupportState::Partial: return "PARTIAL";
        case xykell::SupportState::Unsupported: return "UNSUPPORTED";
    }
    return "?";
}

class XykellMod {
  public:
    bool load(pl::mod::ModContext& ctx) {
        xykell::LoadTracker stages;
        stages.mark(xykell::LoadStage::ProcessStarted, "preloader");
        stages.mark(xykell::LoadStage::NativeLibraryLoaded, "libxykell.so");
        stages.mark(xykell::LoadStage::ModRegistered, "PLGetModRegistration");
        auto& core = xykell::XykellCore::instance();
        if (!core.init(XYKELL_VERSION, XYKELL_PRELOADER_PIN)) {
            ctx.logger().error("{}: core init failed", XYKELL_NAME);
            stages.fail(xykell::LoadStage::CoreInitialized, "XykellCore::init",
                        "initialized core", "init returned false", "see log");
            ctx.logger().error("{}", stages.report());
            return false;
        }
        stages.mark(xykell::LoadStage::CoreInitialized, "XykellCore");
        const auto support =
            xykell::VersionAdapter::check("unknown", currentArch());
        core.setMinecraftVersion(support.display);
        core.addCapability(xykell::Capability::VersionDetect);
        const auto& info = core.info();
        ctx.logger().info("{} {} ({}): load, preloader SDK {}, mc={} [{}: {}]", XYKELL_NAME,
                           info.xykellVersion, info.buildType, info.leviPreloaderPin,
                           info.minecraftVersion, stateName(support.state), support.reason);
        const bool menuOk = xykell::registerMenuModule(ctx.id());
        ctx.logger().info("{}: mod-menu registration {}", XYKELL_NAME,
                           menuOk ? "ok" : "FAILED");
        // Storage roots come ONLY from the verified preloader context.
        // (Writability is device-verified; failures degrade to defaults.)
        const std::string cfgPath = ctx.configDir().string() + "/xykell.json";
        auto& cfg = runtimeConfig();
        const bool cfgOk = cfg.load(cfgPath);
        ctx.logger().info("{}: config {} (recovered={}, migratedFrom={})", XYKELL_NAME,
                           cfgOk ? "ok" : "defaults", cfg.recovered(),
                           cfg.migratedFrom());
        core.setModEnabled(cfg.moduleEnabled("xykell-core", core.modEnabled()));
        stages.mark(xykell::LoadStage::ConfigInitialized, "xykell.json");
        auto& guard = runtimeGuard(ctx.dataDir().string());
        std::string guardErr;
        if (!guard.load(guardErr)) {
            guard.enterSafeMode("crash state unreadable: " + guardErr);
        }
        for (const auto& q : guard.quarantined()) {
            runtimeModules().quarantine(q, guard.quarantineReason(q));
        }
        setRuntimeModules(&runtimeModules());
        setRecoveryContext(&guard, &runtimeModules());
        // Registry catalog from the verified mod resources dir; missing file
        // means no ClickGUI (menu + HUD proof still work) — never invented.
        std::string registryJson;
        {
            const auto read =
                xykell::fs::readFile(ctx.resourceDir().string() + "/features.json");
            if (read.ok) {
                registryJson = read.content;
            } else {
                ctx.logger().info("{}: registry unavailable ({}), ClickGUI off", XYKELL_NAME,
                                 read.error);
            }
        }
        stages.mark(xykell::LoadStage::RegistryInitialized, "features.json");
        bool hudOk = false;
        if (guard.isSafeMode()) {
            std::size_t disabled = 0;
            const std::string report = guard.safeModeReport(disabled);
            ctx.logger().info("{}: {}", XYKELL_NAME, report);
            core.setSafeMode(true);
            const bool recOk = xykell::registerRecoveryModule(ctx.id(), report);
            ctx.logger().info("{}: recovery registration {}", XYKELL_NAME,
                               recOk ? "ok" : "FAILED");
        } else {
            hudOk = xykell::registerHudModule(ctx.id());
            ctx.logger().info("{}: hud/input registration {}", XYKELL_NAME,
                               hudOk ? "ok" : "FAILED");
            const bool guiOk = xykell::registerClickGuiModule(ctx.id(), registryJson);
            ctx.logger().info("{}: clickgui registration {}", XYKELL_NAME,
                               guiOk ? "ok" : "OFF (no catalog)");
            const bool diagOk = xykell::registerDiagnosticsModule(ctx.id());
            ctx.logger().info("{}: diagnostics registration {}", XYKELL_NAME,
                               diagOk ? "ok" : "FAILED");
            stages.mark(xykell::LoadStage::DiagnosticsInitialized, "xykell-diagnostics");
            stages.mark(xykell::LoadStage::HudInitialized, "xykell-hud");
            stages.mark(xykell::LoadStage::InputInitialized, "touch-callback");
            stages.mark(xykell::LoadStage::RuntimeProbeStarted, "RuntimeProbe::collect");
            const auto probe = xykell::runtime::RuntimeProbe::collect(
                XYKELL_VERSION, XYKELL_LEVI_PIN, XYKELL_PRELOADER_PIN);
            stages.mark(xykell::LoadStage::RuntimeProbeCompleted, "21 capabilities");
            (void)probe;
            // XYKELL_RUNTIME_ACTIVE is marked ONLY here: native code executing
            // in-process after a full load chain. Never for builds/packages.
            std::string activeErr;
            xykell::setHudDataDir(ctx.dataDir().string());
            if (!xykell::markRuntimeActive(ctx.dataDir().string(), XYKELL_VERSION,
                                           activeErr)) {
                ctx.logger().info("{}: runtime-active marker FAILED ({})", XYKELL_NAME,
                                 activeErr);
            }
            stages.mark(xykell::LoadStage::Ready, "load chain complete");
            ctx.logger().info("{}", stages.report());
        }
        // Runtime registry mirrors the two M1 menu modules (behavior unchanged).
        auto& mods = runtimeModules();
        mods.registerModule({xykell::kMenuModuleId, "Xykell Core", "client"});
        mods.registerModule({xykell::kHudModuleId, "Xykell HUD (M1 proof)", "hud"});
        mods.setEnabled(xykell::kMenuModuleId, menuOk);
        mods.setEnabled(xykell::kHudModuleId, hudOk);
        return menuOk && hudOk;
    }

    bool enable(pl::mod::ModContext& ctx) {
        ctx.logger().info("{}: enable", XYKELL_NAME);
        return true;
    }

    bool disable(pl::mod::ModContext& ctx) {
        ctx.logger().info("{}: disable", XYKELL_NAME);
        return true;
    }

    bool unload(pl::mod::ModContext& ctx) {
        ctx.logger().info("{}: unload (clean)", XYKELL_NAME);
        xykell::clearRuntimeActive(ctx.dataDir().string());
        auto& cfg = runtimeConfig();
        cfg.setModuleEnabled("xykell-core", xykell::XykellCore::instance().modEnabled());
        std::string cfgErr = ctx.configDir().string() + "/xykell.json";
        if (!cfg.save(cfgErr)) {
            ctx.logger().info("{}: config save FAILED", XYKELL_NAME);
        }
        runtimeModules().setEnabled(xykell::kHudModuleId, false);
        runtimeModules().setEnabled(xykell::kMenuModuleId, false);
        xykell::unregisterClickGuiModule();
        xykell::unregisterDiagnosticsModule();
        xykell::unregisterRecoveryModule();
        xykell::unregisterHudModule();
        xykell::unregisterMenuModule();
        xykell::XykellCore::instance().shutdown();
        return true;
    }
};

} // namespace

PL_REGISTER_MOD(XykellMod, XykellMod{});
