// Xykell M1 skeleton: lifecycle entry only. No hooks, no game access.
#include <pl/Mod.hpp>

#include "xykell/version.h"

namespace {

class XykellMod {
  public:
    bool load(pl::mod::ModContext& ctx) {
        ctx.logger().info("{} {}: load (preloader SDK {})", XYKELL_NAME, XYKELL_VERSION,
                           XYKELL_PRELOADER_PIN);
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
        return true;
    }
};

} // namespace

PL_REGISTER_MOD(XykellMod, XykellMod{});
