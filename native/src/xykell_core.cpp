#include "xykell/core.h"

namespace xykell {

XykellCore& XykellCore::instance() {
    static XykellCore core;
    return core;
}

bool XykellCore::init(const std::string& xykellVersion, const std::string& preloaderPin) {
    if (info_.initialized) {
        return true;
    }
    info_.xykellVersion = xykellVersion;
    info_.leviPreloaderPin = preloaderPin;
#ifdef NDEBUG
    info_.buildType = "MinSizeRel";
#else
    info_.buildType = "Debug";
#endif
    info_.minecraftVersion = "unknown";
    info_.capabilities = static_cast<std::uint32_t>(Capability::Lifecycle)
                       | static_cast<std::uint32_t>(Capability::Logging);
    info_.initialized = true;
    return true;
}

void XykellCore::shutdown() {
    info_.initialized = false;
    info_.capabilities = 0;
}

void XykellCore::addCapability(Capability c) {
    info_.capabilities |= static_cast<std::uint32_t>(c);
}

} // namespace xykell
