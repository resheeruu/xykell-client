#pragma once

// Profile application (Batch 5): applies a loaded Profile to the live
// managers. Two-phase and honest: phase 1 validates settings + HUD
// layout WITHOUT mutating anything; phase 2 applies modules, settings,
// and layout only if phase 1 is clean. Invalid data is collected with
// explicit reasons; unknown module ids are reported, never fail the
// whole apply (forward compatibility); quarantined modules stay off.
// Keybind (`input`) application is out of scope: no keybind persistence
// format exists yet, so the section is validated as an object and left
// untouched (documented, not silently adopted).
//
// Asymmetry note: unknown MODULE ids are tolerated (reported, apply
// proceeds — forward compatibility for future module ids), while ANY
// settings/hud validation error aborts before mutating anything
// (settings change behavior silently, so partial application would lie).
#include <string>
#include <tuple>
#include <vector>

#include "xykell/config_store.h"
#include "xykell/hud_model.h"
#include "xykell/module_manager.h"
#include "xykell/profile_manager.h"
#include "xykell/settings.h"

namespace xykell {

struct ApplyReport {
    std::vector<std::string> appliedModules;
    std::vector<std::string> unknownModules;
    std::vector<std::string> refusedModules;  // e.g. quarantined
    std::vector<std::string> settingErrors;
    bool hudApplied = false;
    std::string hudError;
    std::string theme;

    bool ok() const {
        return unknownModules.empty() && refusedModules.empty() && settingErrors.empty()
            && hudError.empty();
    }
};

inline ApplyReport applyProfile(const Profile& p, ModuleManager& mods, hud::HudManager& hud,
                                XykellConfig& cfg) {
    ApplyReport report;
    // Phase 1: validate without mutating.
    if (p.settings.isObject()) {
        const json::Object& root = p.settings.asObject(json::Value::emptyObject());
        for (const auto& [section, obj] : root) {
            if (section == "input") {
                if (!obj.isObject()) {
                    report.settingErrors.push_back("section input is not an object");
                }
                continue;  // keybind format pending: object-checked, untouched
            }
            if (!obj.isObject()) {
                report.settingErrors.push_back("section " + section + " is not an object");
                continue;
            }
            const json::Object& inner = obj.asObject(json::Value::emptyObject());
            for (const auto& [key, value] : inner) {
                const settings::SettingSpec* spec = settings::find(section, key);
                if (spec == nullptr) {
                    report.settingErrors.push_back("unknown setting " + section + "." + key);
                    continue;
                }
                std::string error;
                if (!settings::validate(*spec, value, error)) {
                    report.settingErrors.push_back(section + "." + key + ": " + error);
                    continue;
                }
            }
        }
    } else {
        report.settingErrors.push_back("settings is not an object");
    }
    hud::HudLayout scratch;
    bool hudValid = true;
    std::string hudError;
    if (p.hudLayout.isObject()) {
        const json::Object& hl = p.hudLayout.asObject(json::Value::emptyObject());
        if (!hl.empty() && !scratch.deserialize(p.hudLayout, hudError)) {
            hudValid = false;
        }
    } else {
        hudValid = false;
        hudError = "hudLayout is not an object";
    }
    // Re-resolve validated settings values for phase 2.
    std::vector<std::tuple<std::string, std::string, json::Value>> settingsResolved;
    if (report.settingErrors.empty()) {
        const json::Object& root = p.settings.asObject(json::Value::emptyObject());
        for (const auto& [section, obj] : root) {
            if (section == "input") {
                continue;
            }
            const json::Object& inner = obj.asObject(json::Value::emptyObject());
            for (const auto& [key, value] : inner) {
                settingsResolved.emplace_back(section, key, value);
            }
        }
    }
    if (!report.settingErrors.empty() || !hudValid) {
        report.hudError = hudError;
        return report;  // nothing mutated
    }
    // Phase 2: apply. Modules first (per-module, infallible except policy).
    for (const auto& [id, on] : p.modules) {
        if (mods.get(id) == nullptr) {
            report.unknownModules.push_back(id);
            continue;
        }
        if (!mods.setEnabled(id, on)) {
            report.refusedModules.push_back(id);
            continue;
        }
        report.appliedModules.push_back(id);
    }
    for (const auto& [section, key, value] : settingsResolved) {
        const settings::SettingSpec* spec = settings::find(section, key);
        std::string error;
        if (spec == nullptr || !settings::setValue(cfg, *spec, value, error)) {
            report.settingErrors.push_back(section + "." + key + ": " + error);
        }
    }
    if (!p.hudLayout.asObject(json::Value::emptyObject()).empty()) {
        std::string error;
        if (hud.loadLayout(p.hudLayout, error)) {
            report.hudApplied = true;
        } else {
            report.hudError = error;  // validated above; defensive only
        }
    }
    if (p.theme.empty()) {
        report.settingErrors.push_back("theme is empty");
    } else {
        report.theme = p.theme;
    }
    return report;
}

} // namespace xykell
