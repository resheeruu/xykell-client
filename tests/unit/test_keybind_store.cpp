// Host unit test: keybind persistence store (Batch 13). Deterministic,
// no platform: missing file = clean start, round-trip of abstract codes,
// skipped unknown/conflicting/invalid entries, corrupt + wrong-version
// recovery with a kept backup, repair-by-save, atomic parent-dir creation.
#include <cassert>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <string>

#include "xykell/file_util.h"
#include "xykell/keybind_store.h"

namespace {
std::string tmpBase() {
    const char* e = std::getenv("XYKELL_TEST_TMP");
    return e != nullptr ? e : "/data/data/com.termux/files/usr/tmp/opencode";
}
std::string tmp(const char* n) { return tmpBase() + "/kbind-" + std::string(n); }
int corruptCount(const std::string& dir) {
    int n = 0;
    std::error_code ec;
    for (const auto& e : std::filesystem::directory_iterator(dir, ec)) {
        if (e.path().filename().string().find(".corrupt.") != std::string::npos) {
            ++n;
        }
    }
    return n;
}
bool writeFile(const std::string& path, const std::string& content) {
    std::string err;
    return xykell::fs::atomicWrite(path, content, err);
}
} // namespace

int main() {
    using namespace xykell;
    using namespace xykell::input;
    const std::string dir = tmp("1");
    const std::string f = dir + "/keybinds.json";
    std::string err;
    std::error_code ec;
    std::filesystem::remove_all(dir, ec);
    assert(fs::ensureDir(dir, err));

    // --- missing file: clean start, defaults untouched ---
    {
        KeybindManager m;
        assert(registerDefaultBinds(m) == 8);
        assert(loadBinds(m, f, err));
        assert(err.empty());
        assert(!m.isBound("hud.editor.open"));
        assert(m.list().size() == 8);
    }

    // --- round-trip: host key codes and touch-region codes survive ---
    {
        KeybindManager m;
        registerDefaultBinds(m);
        assert(m.bind("hud.editor.open", 101));
        assert(m.bind("module.toggle.fps", 200, true));
        assert(m.bind("profile.next", kTouchBase - 3));
        assert(saveBinds(m, f, err));
        KeybindManager m2;
        registerDefaultBinds(m2);
        assert(loadBinds(m2, f, err));
        assert(err.empty());
        assert(m2.get("hud.editor.open").primary == 101);
        assert(m2.get("module.toggle.fps").primary == 0);
        assert(m2.get("module.toggle.fps").secondary == 200);
        assert(m2.get("profile.next").primary == kTouchBase - 3);
        assert(m2.boundCodes().size() == 3);
    }

    // --- file stores only bound actions, with version ---
    {
        const auto r = fs::readFile(f);
        assert(r.ok);
        assert(r.content.find("\"version\":1") != std::string::npos);
        assert(r.content.find("\"hud.editor.commit\"") == std::string::npos);
    }

    // --- unknown action skipped with a note; known ones applied ---
    {
        assert(writeFile(f,
            "{\"version\":1,\"binds\":{"
            "\"nope.action\":{\"p\":111,\"s\":0},"
            "\"module.toggle.cps\":{\"p\":222,\"s\":0}}}"));
        KeybindManager m;
        registerDefaultBinds(m);
        assert(loadBinds(m, f, err));
        assert(!err.empty());
        assert(err.find("nope.action") != std::string::npos);
        assert(err.find("unknown action") != std::string::npos);
        assert(m.get("module.toggle.cps").primary == 222);
        assert(m.press(111).empty());  // unknown never bound
        assert(m.get("nope.action").action.empty());
    }

    // --- conflicting code: later entry skipped, earlier kept (map order) ---
    {
        assert(writeFile(f,
            "{\"version\":1,\"binds\":{"
            "\"hud.editor.open\":{\"p\":333,\"s\":0},"
            "\"module.toggle.clock\":{\"p\":333,\"s\":0}}}"));
        KeybindManager m;
        registerDefaultBinds(m);
        assert(loadBinds(m, f, err));
        assert(!err.empty());
        assert(err.find("module.toggle.clock") != std::string::npos);
        const auto fired = m.press(333);
        assert(fired.size() == 1 && fired[0] == "hud.editor.open");
        assert(!m.isBound("module.toggle.clock"));
    }

    // --- invalid codes (out of range, non-integral) skipped ---
    {
        assert(writeFile(f,
            "{\"version\":1,\"binds\":{"
            "\"profile.next\":{\"p\":1000000000,\"s\":0},"
            "\"observation.start\":{\"p\":1.5,\"s\":0}}}"));
        KeybindManager m;
        registerDefaultBinds(m);
        assert(loadBinds(m, f, err));
        assert(!err.empty());
        assert(err.find("profile.next") != std::string::npos);
        assert(err.find("observation.start") != std::string::npos);
        assert(!m.isBound("profile.next"));
        assert(!m.isBound("observation.start"));
    }

    // --- degenerate secondary == primary: secondary dropped ---
    {
        assert(writeFile(f,
            "{\"version\":1,\"binds\":{\"profile.next\":{\"p\":444,\"s\":444}}}"));
        KeybindManager m;
        registerDefaultBinds(m);
        assert(loadBinds(m, f, err));
        assert(err.empty());
        assert(m.get("profile.next").primary == 444);
        assert(m.get("profile.next").secondary == 0);
        assert(m.boundCodes().size() == 1);
    }

    // --- corrupt JSON: recovered, backup kept, error explains ---
    {
        assert(writeFile(f, "{not json at all"));
        KeybindManager m;
        registerDefaultBinds(m);
        assert(loadBinds(m, f, err));
        assert(!err.empty());
        assert(err.find("corrupt") != std::string::npos);
        assert(err.find(".corrupt.") != std::string::npos);
        assert(corruptCount(dir) == 1);
        assert(!m.isBound("hud.editor.open"));  // nothing applied
    }

    // --- wrong version: recovered, backup kept ---
    {
        assert(writeFile(f, "{\"version\":99,\"binds\":{}}"));
        KeybindManager m;
        registerDefaultBinds(m);
        assert(loadBinds(m, f, err));
        assert(!err.empty());
        assert(err.find("version") != std::string::npos);
        assert(corruptCount(dir) == 2);
    }

    // --- repair path: save after corruption, reload clean ---
    {
        KeybindManager m;
        registerDefaultBinds(m);
        assert(m.bind("observation.start", 500));
        assert(saveBinds(m, f, err));
        KeybindManager m2;
        registerDefaultBinds(m2);
        assert(loadBinds(m2, f, err));
        assert(err.empty());
        assert(m2.get("observation.start").primary == 500);
    }

    // --- save creates missing parent dirs ---
    {
        const std::string nested = dir + "/sub/dir/keybinds.json";
        KeybindManager m;
        registerDefaultBinds(m);
        assert(m.bind("observation.stop", 600));
        assert(saveBinds(m, nested, err));
        assert(fs::readFile(nested).ok);
        KeybindManager m2;
        registerDefaultBinds(m2);
        assert(loadBinds(m2, nested, err));
        assert(err.empty());
        assert(m2.get("observation.stop").primary == 600);
    }

    std::cout << "test_keybind_store OK\n";
    return 0;
}
