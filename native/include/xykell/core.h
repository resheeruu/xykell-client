#pragma once

#include <cstdint>
#include <string>

// Minimal Xykell Core state. Pure C++: no Android, no preloader, no game
// headers — deliberately unit-testable on any host.
namespace xykell {

enum class Capability : std::uint32_t {
    None = 0,
    Lifecycle = 1u << 0, // load/enable/disable/unload proven
    Logging = 1u << 1,   // startup/shutdown log lines emitted
    Config = 1u << 2,    // (Task 4) persistent config
    VersionDetect = 1u << 3, // (Task 3) Bedrock version detection
    Hud = 1u << 4,       // (Task 5) overlay proof
    Input = 1u << 5,     // (Task 5) input callback proof
};

struct CoreInfo {
    std::string xykellVersion;
    std::string buildType;
    std::string minecraftVersion; // "unknown" until VersionAdapter runs
    std::string leviPreloaderPin;
    std::uint32_t capabilities = 0;
    bool initialized = false;
    bool safeMode = false;
    // Mod Menu proof state (Task 4). Persistence owner: Levi Mod Menu
    // (device-verified in Task 6); mirrored here for runtime behavior.
    bool modEnabled = true;
    bool debugLogging = false;
};

class XykellCore {
  public:
    static XykellCore& instance();

    // Idempotent: second init() is a no-op returning true.
    bool init(const std::string& xykellVersion, const std::string& preloaderPin);
    void shutdown();

    void setSafeMode(bool on) { info_.safeMode = on; }
    void setModEnabled(bool on) { info_.modEnabled = on; }
    void setDebugLogging(bool on) { info_.debugLogging = on; }
    void setMinecraftVersion(const std::string& v) { info_.minecraftVersion = v; }
    void addCapability(Capability c);

    const CoreInfo& info() const { return info_; }
    bool initialized() const { return info_.initialized; }
    bool modEnabled() const { return info_.modEnabled; }

  private:
    XykellCore() = default;
    CoreInfo info_;
};

} // namespace xykell
