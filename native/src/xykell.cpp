// Xykell M1: lifecycle entry + Core init/shutdown. No hooks, no game access.
#include <pl/Mod.hpp>

#include "xykell/core.h"
#include "xykell/version.h"
#include "xykell/version_adapter.h"

namespace {

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
        auto& core = xykell::XykellCore::instance();
        if (!core.init(XYKELL_VERSION, XYKELL_PRELOADER_PIN)) {
            ctx.logger().error("{}: core init failed", XYKELL_NAME);
            return false;
        }
        const auto support =
            xykell::VersionAdapter::check("unknown", currentArch());
        core.setMinecraftVersion(support.display);
        core.addCapability(xykell::Capability::VersionDetect);
        const auto& info = core.info();
        ctx.logger().info("{} {} ({}): load, preloader SDK {}, mc={} [{}: {}]", XYKELL_NAME,
                           info.xykellVersion, info.buildType, info.leviPreloaderPin,
                           info.minecraftVersion, stateName(support.state), support.reason);
        return true;
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
        xykell::XykellCore::instance().shutdown();
        return true;
    }
};

} // namespace

PL_REGISTER_MOD(XykellMod, XykellMod{});
