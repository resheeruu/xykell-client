#pragma once

// XYKELL_RUNTIME_ACTIVE marker: set ONLY at the end of a successful in-load
// (i.e. native code actually executing inside the host process). Never set
// for builds, packages, launcher opens, or config loads. Persisted as a small
// file in the mod data dir; cleared on clean unload. Stale marker (crash)
// means "was active, did not shut down cleanly" — surfaced, not hidden.
#include <string>

namespace xykell {

inline constexpr const char* kActiveFileName = "runtime_active";

bool markRuntimeActive(const std::string& dataDir, const std::string& buildId,
                       std::string& error);
bool isRuntimeActive(const std::string& dataDir);
void clearRuntimeActive(const std::string& dataDir);
std::string readActiveBuild(const std::string& dataDir);

} // namespace xykell
