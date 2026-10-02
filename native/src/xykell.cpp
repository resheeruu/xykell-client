// Xykell M1: lifecycle entry + Core init/shutdown. No hooks, no game access.
#include <pl/Mod.hpp>

#include "xykell/core.h"
#include "xykell/version.h"

namespace {

class XykellMod {
  public:
    bool load(pl::mod::ModContext& ctx) {
        auto& core = xykell::XykellCore::instance();
        if (!core.init(XYKELL_VERSION, XYKELL_PRELOADER_PIN)) {
            ctx.logger().error("{}: core init failed", XYKELL_NAME);
            return false;
        }
        const auto& info = core.info();
        ctx.logger().info("{} {} ({}): load, preloader SDK {}, mc={}", XYKELL_NAME,
                           info.xykellVersion, info.buildType, info.leviPreloaderPin,
                           info.minecraftVersion);
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
