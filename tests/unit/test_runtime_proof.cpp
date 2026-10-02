// Host unit test: runtime-active marker lifecycle + proof banner text.
#include <cassert>
#include <cstdlib>
#include <iostream>

#include "xykell/file_util.h"
#include "xykell/hud_renderer.h"
#include "xykell/runtime_active.h"

namespace {
std::string base() {
    const char* e = std::getenv("XYKELL_TEST_TMP");
    return (e != nullptr ? std::string(e) : "/data/data/com.termux/files/usr/tmp/opencode")
         + "/xactive-1";
}
} // namespace

int main() {
    using namespace xykell;
    const std::string dir = base() + "/mod";
    std::string err;
    // Fresh dir: not active.
    assert(!isRuntimeActive(dir));
    assert(readActiveBuild(dir).empty());
    // Mark only succeeds with a real write; then active.
    assert(markRuntimeActive(dir, "0.1.0-proof", err));
    assert(isRuntimeActive(dir));
    assert(readActiveBuild(dir) == "0.1.0-proof");
    // Clean unload clears.
    clearRuntimeActive(dir);
    assert(!isRuntimeActive(dir));

    // Proof banner is exact, ASCII-only, unmistakable.
    const auto lines = hud::proofBanner("XYKELL 0.1.0 (mc=unknown)");
    assert(lines.size() == 4);
    assert(lines[1].text == "| XYKELL CLIENT RUNTIME ACTIVE |");
    assert(lines[3].text == "XYKELL 0.1.0 (mc=unknown)");
    for (const auto& l : lines) {
        for (const char c : l.text) {
            assert(static_cast<unsigned char>(c) < 0x80);
        }
    }

    std::cout << "test_runtime_proof: PASS\n";
    return 0;
}
