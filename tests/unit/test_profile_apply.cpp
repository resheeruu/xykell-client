// Host unit test: profile application to live managers (Batch 5).
// Deterministic; no platform, no I/O.
#include <cassert>
#include <cstdlib>
#include <iostream>
#include <string>

#include "xykell/hud_sources.h"
#include "xykell/profile_apply.h"

using namespace xykell;

namespace {
Profile makeProfile() {
    Profile p;
    p.name = "Test";
    p.modules["xykell.hud.fps"] = true;
    p.modules["xykell.hud.unknown_future"] = true;
    p.theme = "Xykell Dark";
    return p;
}

void setSetting(Profile& p, const char* section, const char* key, json::Value v) {
    json::Object& root =
        const_cast<json::Object&>(p.settings.asObject(json::Value::emptyObject()));
    auto sit = root.find(section);
    if (sit == root.end()) {
        sit = root.emplace(section, json::Value(json::Object{})).first;
    }
    json::Object& inner =
        const_cast<json::Object&>(sit->second.asObject(json::Value::emptyObject()));
    inner[key] = std::move(v);
}
} // namespace

int main() {
    // --- clean apply: modules, settings, theme; unknown tolerated ---
    {
        ModuleManager mods;
        assert(hud::sources::registerLocalModules(mods) == 5);
        hud::HudManager hud;
        XykellConfig cfg;
        Profile p = makeProfile();
        setSetting(p, "client", "animations", json::Value(false));
        setSetting(p, "hud", "scale", json::Value(2.0));
        const ApplyReport r = applyProfile(p, mods, hud, cfg);
        assert(!r.ok());  // unknown id reported (forward-compat), rest applied
        assert(r.unknownModules.size() == 1);
        assert(r.appliedModules.size() == 1);
        assert(mods.get("xykell.hud.fps")->state == ModuleState::Enabled);
        bool on = true;
        assert(settings::getBool(cfg, *settings::find("client", "animations"), on) && !on);
        assert(r.theme == "Xykell Dark");
        assert(r.hudApplied == false);  // empty layout: current kept
    }
    // --- invalid settings abort before mutating anything ---
    {
        ModuleManager mods;
        hud::sources::registerLocalModules(mods);
        hud::HudManager hud;
        XykellConfig cfg;
        Profile p = makeProfile();
        setSetting(p, "hud", "scale", json::Value(99.0));  // out of range
        setSetting(p, "nope", "x", json::Value(true));     // unknown section key
        const ApplyReport r = applyProfile(p, mods, hud, cfg);
        assert(!r.ok());
        assert(r.settingErrors.size() == 2);
        assert(mods.get("xykell.hud.fps")->state == ModuleState::Loaded);  // untouched
        bool on = true;
        assert(settings::getBool(cfg, *settings::find("client", "animations"), on) && on);
    }
    // --- quarantined modules are refused, not enabled ---
    {
        ModuleManager mods;
        hud::sources::registerLocalModules(mods);
        mods.quarantine("xykell.hud.fps", "test");
        hud::HudManager hud;
        XykellConfig cfg;
        Profile p = makeProfile();
        const ApplyReport r = applyProfile(p, mods, hud, cfg);
        assert(!r.ok());
        assert(r.refusedModules.size() == 1);
        assert(mods.get("xykell.hud.fps")->state == ModuleState::Quarantined);
    }
    // --- malformed profile shapes rejected ---
    {
        ModuleManager mods;
        hud::HudManager hud;
        XykellConfig cfg;
        Profile p;
        p.settings = json::Value(true);  // not an object
        p.theme = "";
        ApplyReport r = applyProfile(p, mods, hud, cfg);
        assert(!r.ok());
        // hudLayout non-object rejected too.
        Profile q;
        q.hudLayout = json::Value(42);
        r = applyProfile(q, mods, hud, cfg);
        assert(!r.ok() && !r.hudError.empty());
    }
    // --- builtin Recording preset content (Batch 5 requirement) ---
    {
        const char* tmp = std::getenv("XYKELL_TEST_TMP");
        ProfileManager pm(std::string(tmp != nullptr ? tmp : "/tmp") + "/xprof-apply");
        std::string err;
        bool hasRecording = false;
        for (const auto& n : ProfileManager::builtinNames()) {
            if (n == "Recording") {
                hasRecording = true;
            }
        }
        assert(hasRecording);
        assert(pm.reset("Recording", err));
        Profile rec;
        assert(pm.load("Recording", rec, err));
        assert(rec.modules.empty() && rec.theme == "Xykell Minimal");
        // Recording applies cleanly (empty modules/layout, valid settings).
        ModuleManager mods;
        hud::sources::registerLocalModules(mods);
        hud::HudManager hud;
        XykellConfig cfg;
        const ApplyReport r = applyProfile(rec, mods, hud, cfg);
        assert(r.ok());
        bool reduced = false;
        assert(settings::getBool(cfg, *settings::find("client", "reduced_motion"), reduced)
               && reduced);
    }

    std::cout << "test_profile_apply: PASS\n";
    return 0;
}
