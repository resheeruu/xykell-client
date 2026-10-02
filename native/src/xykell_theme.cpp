#include "xykell/theme.h"

namespace xykell::ui {

json::Value Theme::serialize() const {
    json::Object o;
    o.emplace("name", json::Value(name));
    o.emplace("background", json::Value(background));
    o.emplace("surface", json::Value(surface));
    o.emplace("accent", json::Value(accent));
    o.emplace("text", json::Value(text));
    o.emplace("muted", json::Value(muted));
    o.emplace("warning", json::Value(warning));
    o.emplace("error", json::Value(error));
    o.emplace("success", json::Value(success));
    o.emplace("supported", json::Value(supported));
    o.emplace("partial", json::Value(partial));
    o.emplace("unavailable", json::Value(unavailable));
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
    if (!getColor("background", tmp.background) || !getColor("surface", tmp.surface)
        || !getColor("accent", tmp.accent) || !getColor("text", tmp.text)
        || !getColor("muted", tmp.muted) || !getColor("warning", tmp.warning)
        || !getColor("error", tmp.error) || !getColor("success", tmp.success)
        || !getColor("supported", tmp.supported) || !getColor("partial", tmp.partial)
        || !getColor("unavailable", tmp.unavailable)) {
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
        return std::vector<Theme>{dark, light};
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
