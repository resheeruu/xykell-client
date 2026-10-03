// Host unit test: settings theme Choice must stay in lockstep with the
// ThemeManager builtin set (Batch 10). A theme that exists but cannot be
// selected, or an option with no theme behind it, is a product defect —
// this suite fails on either direction of drift.
#include <cassert>
#include <iostream>
#include <set>
#include <string>

#include "xykell/config_store.h"
#include "xykell/settings.h"
#include "xykell/theme.h"

int main() {
    using namespace xykell;

    const settings::SettingSpec* themeSpec = settings::find("client", "theme");
    assert(themeSpec != nullptr);
    assert(themeSpec->type == settings::SettingType::Choice);

    // Every builtin is a legal option; every option has a builtin.
    std::set<std::string> options(themeSpec->options.begin(), themeSpec->options.end());
    std::set<std::string> builtinNames;
    for (const auto& t : ui::ThemeManager::builtins()) {
        builtinNames.insert(t.name);
    }
    assert(options.size() == themeSpec->options.size());  // no duplicates
    assert(options == builtinNames);
    assert(builtinNames.size() >= 7);

    // The declared default must be selectable.
    const std::string def = themeSpec->defaultValue.asString("");
    assert(options.count(def) == 1);

    // Validation: every builtin accepted, unknown rejected.
    for (const auto& name : builtinNames) {
        std::string err;
        assert(settings::validate(*themeSpec, json::Value(name), err));
    }
    std::string err;
    assert(!settings::validate(*themeSpec, json::Value(std::string("Xykell Nonexistent")),
                               err));
    assert(!err.empty());

    // Through a live config: set each builtin, reject unknown, previous
    // value kept after rejection.
    XykellConfig cfg;
    for (const auto& name : builtinNames) {
        std::string e;
        assert(settings::setValue(cfg, *themeSpec, json::Value(name), e));
        assert(cfg.getString("client", "theme", "?") == name);
    }
    {
        std::string e;
        const std::string before = cfg.getString("client", "theme", "?");
        assert(!settings::setValue(cfg, *themeSpec, json::Value(std::string("bogus")), e));
        assert(!e.empty());
        assert(cfg.getString("client", "theme", "?") == before);
    }

    // Legacy configs that stored one of the earlier 3 names still validate.
    for (const char* legacy : {"Xykell Dark", "Xykell Midnight", "Xykell Minimal"}) {
        std::string e;
        assert(settings::validate(*themeSpec, json::Value(std::string(legacy)), e));
    }

    // Serialize round-trip: the theme name survives config JSON.
    const std::string blob = json::stringify(cfg.root());
    const auto parsed = json::parse(blob);
    assert(parsed.ok);
    const json::Object& root = parsed.value.asObject(json::Value::emptyObject());
    const auto ci = root.find("client");
    assert(ci != root.end() && ci->second.isObject());
    const json::Object& inner = ci->second.asObject(json::Value::emptyObject());
    const auto ti = inner.find("theme");
    assert(ti != inner.end());
    assert(ti->second.asString("") == cfg.getString("client", "theme", "?"));

    std::cout << "test_theme_settings: PASS (" << builtinNames.size() << " themes)\n";
    return 0;
}
