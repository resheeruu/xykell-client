// Host unit test: config save/load, corruption recovery, malformed values.
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <iostream>

#include "xykell/config_store.h"
#include "xykell/file_util.h"

namespace {
std::string tmpBase() {
    const char* e = std::getenv("XYKELL_TEST_TMP");
    return e != nullptr ? e : "/data/data/com.termux/files/usr/tmp/opencode";
}
std::string tmp(const char* n) { return tmpBase() + "/xcfg-" + std::string(n); }
} // namespace

int main() {
    using namespace xykell;
    const std::string dir = tmp("1");
    const std::string f = dir + "/xykell.json";
    std::string err;
    assert(fs::ensureDir(dir, err));

    // Missing file -> clean defaults, usable.
    XykellConfig c;
    assert(c.load(f));
    assert(!c.recovered());
    assert(c.moduleEnabled("anything", true));

    // Round-trip with values.
    c.setModuleEnabled("xykell-core", false);
    c.setString("client", "theme", "Xykell Light");
    assert(c.save(f));
    XykellConfig c2;
    assert(c2.load(f));
    assert(!c2.moduleEnabled("xykell-core", true));
    assert(c2.getString("client", "theme", "?") == "Xykell Light");

    // Malformed value type -> safe fallback, no crash.
    assert(c2.getString("client", "theme", "?") == "Xykell Light");
    XykellConfig c3;
    {
        // Write theme as a number directly.
        auto read = fs::readFile(f);
        assert(read.ok);
        auto p = json::parse(read.content);
        assert(p.ok);
        auto& o = const_cast<json::Object&>(p.value.asObject(json::Value::emptyObject()));
        o["client"].asObject(json::Value::emptyObject()); // exists
        auto& client = const_cast<json::Object&>(
            o["client"].asObject(json::Value::emptyObject()));
        client["theme"] = json::Value(42);
        assert(fs::atomicWrite(f, json::stringify(p.value), err));
    }
    assert(c3.load(f));
    assert(c3.getString("client", "theme", "FB") == "FB");

    // Corrupt file -> backup + defaults.
    {
        assert(fs::atomicWrite(f, "{not json", err));
    }
    XykellConfig c4;
    assert(!c4.load(f)); // reports unusable...
    assert(c4.recovered()); // ...but recovered to defaults
    assert(!c4.lastError().empty());
    assert(c4.moduleEnabled("xykell-core", true)); // defaults
    std::cout << "test_config: PASS\n";
    return 0;
}
