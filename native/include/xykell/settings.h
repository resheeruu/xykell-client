#pragma once

// Declarative settings catalog over XykellConfig (Batch 2). Every setting
// that has a UI control MUST be declared here with type, default,
// validation, and description. Settings without a consumer are NOT
// declared (no dead knobs). Import/export validation reuses the same
// specs, so file and UI can never disagree about what is legal.
//
// Policy: invalid writes are REJECTED (false + error), never clamped or
// coerced — the caller decides what to show. Reset restores declared
// defaults section-wide or globally.
#include <string>
#include <vector>

#include "xykell/config_store.h"
#include "xykell/json_min.h"

namespace xykell::settings {

enum class SettingType { Bool, Int, Double, Text, Choice };

struct SettingSpec {
    const char* section;
    const char* key;
    SettingType type;
    json::Value defaultValue;
    double min = 0.0;  // Int/Double range (inclusive)
    double max = 0.0;
    std::vector<std::string> options;  // Choice allowlist
    const char* description;
};

inline json::Value b(bool v) { return json::Value(v); }
inline json::Value i(int v) { return json::Value(v); }
inline json::Value d(double v) { return json::Value(v); }
inline json::Value t(const char* v) { return json::Value(std::string(v)); }

// The catalog. Small on purpose: each entry must have a consumer.
inline const std::vector<SettingSpec>& catalog() {
    static const std::vector<SettingSpec> kSpecs = {
        {"client", "theme", SettingType::Choice, t("Xykell Dark"), 0, 0,
         {"Xykell Dark", "Xykell Midnight", "Xykell Minimal"}, "active theme"},
        {"client", "animations", SettingType::Bool, b(true), 0, 0, {},
         "master animation switch"},
        {"client", "animation_intensity", SettingType::Int, i(100), 0, 100, {},
         "animation intensity percent"},
        {"client", "reduced_motion", SettingType::Bool, b(false), 0, 0, {},
         "accessibility reduced motion"},
        {"hud", "scale", SettingType::Double, d(1.0), 0.5, 3.0, {}, "global HUD scale"},
        {"hud", "opacity", SettingType::Int, i(100), 0, 100, {}, "global HUD opacity percent"},
        {"hud", "show_fps", SettingType::Bool, b(true), 0, 0, {}, "FPS element visibility"},
        {"hud", "show_cps", SettingType::Bool, b(true), 0, 0, {}, "CPS element visibility"},
        {"hud", "show_clock", SettingType::Bool, b(true), 0, 0, {}, "clock element visibility"},
        {"hud", "show_session", SettingType::Bool, b(true), 0, 0, {}, "session element visibility"},
        {"notifications", "enabled", SettingType::Bool, b(true), 0, 0, {}, "toast notifications"},
        {"notifications", "max_shown", SettingType::Int, i(3), 1, 5, {}, "max visible toasts"},
        {"privacy", "crash_reporting", SettingType::Bool, b(true), 0, 0, {},
         "local crash report persistence (never uploaded)"},
        {"profile", "active", SettingType::Text, t("Default"), 0, 0, {}, "active profile name"},
    };
    return kSpecs;
}

inline const SettingSpec* find(const std::string& section, const std::string& key) {
    for (const auto& s : catalog()) {
        if (s.section == section && s.key == key) {
            return &s;
        }
    }
    return nullptr;
}

// Validate a value against a spec. Empty error = valid.
inline bool validate(const SettingSpec& spec, const json::Value& v, std::string& error) {
    switch (spec.type) {
        case SettingType::Bool:
            if (!v.isBool()) {
                error = "expected bool";
                return false;
            }
            return true;
        case SettingType::Int: {
            if (!v.isNumber()) {
                error = "expected number";
                return false;
            }
            const double n = v.asNumber(0.0);
            if (n != static_cast<long long>(n)) {
                error = "expected integer";
                return false;
            }
            if (n < spec.min || n > spec.max) {
                error = "out of range";
                return false;
            }
            return true;
        }
        case SettingType::Double: {
            if (!v.isNumber()) {
                error = "expected number";
                return false;
            }
            const double n = v.asNumber(0.0);
            if (!(n >= spec.min && n <= spec.max)) {
                error = "out of range";
                return false;
            }
            return true;
        }
        case SettingType::Text:
            if (!v.isString() || v.asString("").empty()) {
                error = "expected non-empty text";
                return false;
            }
            return true;
        case SettingType::Choice: {
            if (!v.isString()) {
                error = "expected text choice";
                return false;
            }
            const std::string s = v.asString("");
            for (const auto& o : spec.options) {
                if (o == s) {
                    return true;
                }
            }
            error = "not an allowed option";
            return false;
        }
    }
    error = "unknown type";
    return false;
}

// Typed access against a live config. Set rejects invalid values.
inline bool getBool(const XykellConfig& cfg, const SettingSpec& spec, bool& out) {
    const json::Object& obj = cfg.root().asObject(json::Value::emptyObject());
    const auto sit = obj.find(spec.section);
    if (sit == obj.end() || !sit->second.isObject()) {
        return false;  // unknown section: a store problem, not a default
    }
    const json::Object& inner = sit->second.asObject(json::Value::emptyObject());
    const auto kit = inner.find(spec.key);
    if (kit == inner.end()) {
        // Unset means the declared default (catalog defaults are NOT
        // materialized into the JSON until set/reset writes them).
        if (!spec.defaultValue.isBool()) {
            return false;
        }
        out = spec.defaultValue.asBool(false);
        return true;
    }
    if (!kit->second.isBool()) {
        return false;  // corrupt value surfaces instead of hiding
    }
    out = kit->second.asBool(false);
    return true;
}

inline bool setValue(XykellConfig& cfg, const SettingSpec& spec, const json::Value& v,
                     std::string& error) {
    if (!validate(spec, v, error)) {
        return false;
    }
    if (!cfg.root().isObject()) {
        error = "config root unavailable";
        return false;
    }
    // Same const_cast mutation pattern as config_store's mutableSection:
    // Value stores shared_ptr<Object>; the const accessor is the only
    // reader, so const_cast here is the established convention.
    json::Object& obj =
        const_cast<json::Object&>(cfg.root().asObject(json::Value::emptyObject()));
    auto sit = obj.find(spec.section);
    if (sit == obj.end() || !sit->second.isObject()) {
        error = "unknown section";
        return false;
    }
    json::Object& inner =
        const_cast<json::Object&>(sit->second.asObject(json::Value::emptyObject()));
    inner[spec.key] = v;
    return true;
}

// Restore every catalog default (global or per section).
inline void resetAll(XykellConfig& cfg) {
    for (const auto& s : catalog()) {
        std::string ignored;
        setValue(cfg, s, s.defaultValue, ignored);
    }
}

inline void resetSection(XykellConfig& cfg, const std::string& section) {
    for (const auto& s : catalog()) {
        if (s.section == section) {
            std::string ignored;
            setValue(cfg, s, s.defaultValue, ignored);
        }
    }
}

// Validate a full snapshot (e.g. an import candidate): returns error
// strings, empty when clean. Unknown sections/keys are reported, never
// silently adopted.
inline std::vector<std::string> validateSnapshot(const json::Value& snapshot) {
    std::vector<std::string> errors;
    if (!snapshot.isObject()) {
        return {"snapshot is not an object"};
    }
    const json::Object& root = snapshot.asObject(json::Value::emptyObject());
    for (const auto& [section, obj] : root) {
        if (section == "schemaVersion") {
            continue;  // store metadata, not a setting (config_store's domain)
        }
        if (!obj.isObject()) {
            errors.push_back("section " + section + " is not an object");
            continue;
        }
        const json::Object& inner = obj.asObject(json::Value::emptyObject());
        for (const auto& [key, value] : inner) {
            const SettingSpec* spec = find(section, key);
            if (spec == nullptr) {
                errors.push_back("unknown setting " + section + "." + key);
                continue;
            }
            std::string error;
            if (!validate(*spec, value, error)) {
                errors.push_back(section + "." + key + ": " + error);
            }
        }
    }
    return errors;
}

} // namespace xykell::settings
