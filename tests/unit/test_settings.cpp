// Host unit test: settings catalog over XykellConfig (Batch 2).
// Deterministic; file I/O only under the test tmp dir via config store.
#include <cassert>
#include <cstdlib>
#include <iostream>
#include <string>

#include "xykell/settings.h"

using namespace xykell;
using namespace xykell::settings;

int main() {
    // --- catalog shape: every entry has a consumer, defaults valid ---
    {
        assert(catalog().size() == 14);
        for (const auto& s : catalog()) {
            std::string error;
            assert(validate(s, s.defaultValue, error));  // defaults are self-valid
            assert(find(s.section, s.key) == &s);
        }
        assert(find("nope", "nothing") == nullptr);
    }
    // --- typed set/get with rejection (never clamp/coerce) ---
    {
        XykellConfig cfg;
        std::string error;
        const SettingSpec* theme = find("client", "theme");
        assert(theme != nullptr);
        assert(setValue(cfg, *theme, t("Xykell Midnight"), error));
        assert(cfg.getString("client", "theme", "?") == "Xykell Midnight");
        assert(!setValue(cfg, *theme, t("Lunar Dark"), error));  // not allowlisted
        assert(!error.empty());
        assert(cfg.getString("client", "theme", "?") == "Xykell Midnight");  // unchanged
        const SettingSpec* scale = find("hud", "scale");
        assert(setValue(cfg, *scale, d(2.5), error));
        assert(!setValue(cfg, *scale, d(9.0), error));  // out of range
        assert(!setValue(cfg, *scale, t("big"), error));  // wrong type
        const SettingSpec* intensity = find("client", "animation_intensity");
        assert(!setValue(cfg, *intensity, d(10.5), error));  // int required
        assert(setValue(cfg, *intensity, i(50), error));
        const SettingSpec* anim = find("client", "animations");
        assert(!setValue(cfg, *anim, i(1), error));  // bool required
        bool on = false;
        assert(getBool(cfg, *anim, on) && on);  // default true
        assert(setValue(cfg, *anim, b(false), error));
        assert(getBool(cfg, *anim, on) && !on);
    }
    // --- reset restores declared defaults ---
    {
        XykellConfig cfg;
        std::string error;
        setValue(cfg, *find("client", "theme"), t("Xykell Minimal"), error);
        setValue(cfg, *find("hud", "scale"), d(3.0), error);
        resetSection(cfg, "hud");
        const json::Object& afterHud =
            cfg.root().asObject(json::Value::emptyObject()).at("hud").asObject(
                json::Value::emptyObject());
        assert(afterHud.at("scale").asNumber(-1.0) == 1.0);  // default restored
        assert(cfg.getString("client", "theme", "?") == "Xykell Minimal");  // untouched
        resetAll(cfg);
        assert(cfg.getString("client", "theme", "?") == "Xykell Dark");
        bool on = true;
        assert(getBool(cfg, *find("client", "animations"), on) && on);
    }
    // --- import validation: errors collected, unknowns reported ---
    {
        XykellConfig clean;
        auto snap = clean.root();
        assert(validateSnapshot(snap).empty());  // fresh defaults are clean
        json::Value bad(json::Object{});
        auto& o = const_cast<json::Object&>(bad.asObject(json::Value::emptyObject()));
        json::Value hud(json::Object{});
        auto& h = const_cast<json::Object&>(hud.asObject(json::Value::emptyObject()));
        h["scale"] = d(99.0);            // out of range
        h["bogus"] = b(true);            // unknown key
        o["hud"] = hud;
        o["zzz"] = b(true);              // unknown section shape (not object... is bool)
        const auto errors = validateSnapshot(bad);
        assert(errors.size() == 3);
    }
    // --- persistence roundtrip through the real store ---
    {
        const std::string dir = getenv("XYKELL_TEST_TMP") != nullptr ? getenv("XYKELL_TEST_TMP") : "/tmp";
        const std::string path = dir + "/xcfg-settings-batch2.json";
        XykellConfig cfg;
        std::string error;
        assert(setValue(cfg, *find("profile", "active"), t("PvP"), error));
        assert(cfg.save(path));
        XykellConfig loaded;
        assert(loaded.load(path));
        assert(loaded.getString("profile", "active", "?") == "PvP");
        assert(validateSnapshot(loaded.root()).empty());
    }

    std::cout << "test_settings: PASS\n";
    return 0;
}
