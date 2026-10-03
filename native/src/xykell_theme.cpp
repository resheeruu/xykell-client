#include "xykell/theme.h"

namespace xykell::ui {

json::Value Theme::serialize() const {
    json::Object o;
    o.emplace("name", json::Value(name));
    o.emplace("background", json::Value(background));
    o.emplace("surface", json::Value(surface));
    o.emplace("elevated", json::Value(elevated));
    o.emplace("accent", json::Value(accent));
    o.emplace("text", json::Value(text));
    o.emplace("muted", json::Value(muted));
    o.emplace("border", json::Value(border));
    o.emplace("hudAccent", json::Value(hudAccent));
    o.emplace("warning", json::Value(warning));
    o.emplace("error", json::Value(error));
    o.emplace("success", json::Value(success));
    o.emplace("supported", json::Value(supported));
    o.emplace("partial", json::Value(partial));
    o.emplace("unavailable", json::Value(unavailable));
    o.emplace("opacity", json::Value(opacity));
    o.emplace("radius", json::Value(radius));
    return json::Value(std::move(o));
}

bool Theme::deserialize(const json::Value& v, std::string& error) {
    if (!v.isObject()) {
        error = "theme is not an object";
        return false;
    }
    Theme tmp;
    const auto& o = v.asObject(json::Value::emptyObject());
    const auto nameIt = o.find("name");
    if (nameIt != o.end()) {
        if (!nameIt->second.isString() || nameIt->second.asString("").empty()) {
            error = "theme.name invalid";
            return false;
        }
        tmp.name = nameIt->second.asString("");
    }
    const auto getColor = [&](const char* k, std::string& slot) {
        const auto it = o.find(k);
        if (it != o.end()) {
            if (!it->second.isString() || it->second.asString("").empty()
                || it->second.asString("")[0] != '#') {
                error = std::string("theme.") + k + " is not a #color";
                return false;
            }
            slot = it->second.asString("");
        }
        return true;
    };
    if (!getColor("background", tmp.background) || !getColor("surface", tmp.surface)        || !getColor("elevated", tmp.elevated) || !getColor("accent", tmp.accent) || !getColor("text", tmp.text)
        || !getColor("muted", tmp.muted) || !getColor("border", tmp.border)
        || !getColor("hudAccent", tmp.hudAccent) || !getColor("warning", tmp.warning)
        || !getColor("error", tmp.error) || !getColor("success", tmp.success)
        || !getColor("supported", tmp.supported) || !getColor("partial", tmp.partial)
        || !getColor("unavailable", tmp.unavailable)) {
        return false;
    }
    const auto num = [&](const char* k, double& slot, double lo, double hi) {
        const auto it = o.find(k);
        if (it != o.end()) {
            if (!it->second.isNumber()) {
                error = std::string("theme.") + k + " is not a number";
                return false;
            }
            const double v = it->second.asNumber();
            if (!(v >= lo && v <= hi)) {
                error = std::string("theme.") + k + " out of range";
                return false;
            }
            slot = v;
        }
        return true;
    };
    if (!num("opacity", tmp.opacity, 0.0, 1.0) || !num("radius", tmp.radius, 0.0, 32.0)) {
        return false;
    }
    *this = std::move(tmp);
    return true;
}

const std::vector<Theme>& ThemeManager::builtins() {
    static const std::vector<Theme> themes = [] {
        Theme dark; // defaults above == Xykell Dark (matches app/ colors)
        Theme light;
        light.name = "Xykell Light";
        light.background = "#F2F5FA";
        light.surface = "#FFFFFF";
        light.accent = "#0E7C6F";
        light.text = "#16213A";
        light.muted = "#5A6B85";
        Theme amoled;
        amoled.name = "Xykell AMOLED";
        amoled.background = "#000000";
        amoled.surface = "#0A0A0A";
        amoled.accent = "#4FD8C7";
        amoled.text = "#FFFFFF";
        amoled.muted = "#8A97AD";
        Theme minimal;
        minimal.name = "Xykell Minimal";
        minimal.background = "#1A1D24";
        minimal.surface = "#23262F";
        minimal.accent = "#9AA5B5";
        minimal.text = "#E8EEF7";
        minimal.muted = "#8A97AD";
        minimal.radius = 4.0;
        // Original Xykell palettes (Batch 9): deep-space blue-black with a
        // cold steel accent; teal-violet night with aurora green; charcoal
        // red with a restrained crimson accent. None copied from references.
        Theme midnight;
        midnight.name = "Xykell Midnight";
        midnight.background = "#05070D";
        midnight.surface = "#0B1120";
        midnight.elevated = "#141C33";
        midnight.accent = "#6E9BD8";
        midnight.text = "#DFE7F5";
        midnight.muted = "#7286A3";
        midnight.border = "#1C2742";
        midnight.hudAccent = "#6E9BD8";
        Theme aurora;
        aurora.name = "Xykell Aurora";
        aurora.background = "#071210";
        aurora.surface = "#0D1F1C";
        aurora.elevated = "#14322C";
        aurora.accent = "#3FE0A8";
        aurora.text = "#E2F5EC";
        aurora.muted = "#7FA698";
        aurora.border = "#1B3A33";
        aurora.hudAccent = "#7C6CF0";
        Theme crimson;
        crimson.name = "Xykell Crimson";
        crimson.background = "#120809";
        crimson.surface = "#1F0F11";
        crimson.elevated = "#33161A";
        crimson.accent = "#E0485E";
        crimson.text = "#F7E6E8";
        crimson.muted = "#A37E84";
        crimson.border = "#3D1E23";
        crimson.hudAccent = "#E0485E";
        return std::vector<Theme>{dark, light, amoled, minimal, midnight, aurora, crimson};
    }();
    return themes;
}

bool ThemeManager::find(const std::string& name, Theme& out) {
    for (const auto& t : builtins()) {
        if (t.name == name) {
            out = t;
            return true;
        }
    }
    return false;
}

} // namespace xykell::ui
