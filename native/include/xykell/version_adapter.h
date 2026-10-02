#pragma once

#include <string>
#include <vector>

// VersionAdapter: pure logic, no game access, no pasted offsets.
// Maps a Minecraft build string + arch to a support state.
// The version *source* (who provides the string at runtime) is still
// [RESEARCH REQUIRED]; until then "unknown" yields PARTIAL, never a fake verdict.
namespace xykell {

enum class SupportState {
    Supported,
    Partial,
    Unsupported,
};

struct SupportResult {
    SupportState state = SupportState::Partial;
    std::string reason;
    std::string display; // concrete version or "unknown"
};

class VersionAdapter {
  public:
    // Floor enforced by LeviLaunchroid policy at time of writing.
    static constexpr const char* kMinVersion = "1.21.80";
    // Lines Levi's changelog confirms inbuilt-mod support for.
    static const std::vector<std::string>& knownGood();

    static SupportResult check(const std::string& minecraftVersion, const std::string& arch);
    static bool archSupported(const std::string& arch);

  private:
    // Returns <0 / 0 / >0 comparing dotted version strings; -2 on unparseable.
    static int compareVersions(const std::string& a, const std::string& b);
};

} // namespace xykell
