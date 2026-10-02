#include "xykell/runtime_active.h"

#include "xykell/file_util.h"

namespace xykell {

bool markRuntimeActive(const std::string& dataDir, const std::string& buildId,
                       std::string& error) {
    if (!fs::ensureDir(dataDir, error)) {
        return false;
    }
    return fs::atomicWrite(dataDir + "/" + kActiveFileName, buildId + "\n", error);
}

bool isRuntimeActive(const std::string& dataDir) {
    return fs::readFile(dataDir + "/" + std::string(kActiveFileName)).ok;
}

void clearRuntimeActive(const std::string& dataDir) {
    fs::removeFile(dataDir + "/" + std::string(kActiveFileName));
}

std::string readActiveBuild(const std::string& dataDir) {
    const auto r = fs::readFile(dataDir + "/" + std::string(kActiveFileName));
    if (!r.ok) {
        return {};
    }
    std::string s = r.content;
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) {
        s.pop_back();
    }
    return s;
}

} // namespace xykell
