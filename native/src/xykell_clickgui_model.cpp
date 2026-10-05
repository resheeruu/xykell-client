#include "xykell/clickgui_model.h"

#include <cctype>

namespace xykell::gui {

ParseReport buildFromRegistryJson(const std::string& jsonText) {
    ParseReport rep;
    const auto parsed = json::parse(jsonText);
    if (!parsed.ok || !parsed.value.isObject()) {
        rep.error = "registry: " + parsed.error;
        return rep;
    }
    const auto& root = parsed.value.asObject(json::Value::emptyObject());
    const auto fit = root.find("features");
    if (fit == root.end() || !fit->second.isArray()) {
        rep.error = "registry: missing features array";
        return rep;
    }
    for (const auto& f : fit->second.asArray(json::Value::emptyArray())) {
        if (!f.isObject()) {
            rep.error = "registry: feature is not an object";
            return rep;
        }
        const auto& o = f.asObject(json::Value::emptyObject());
        GuiModuleEntry e;
        const auto get = [&](const char* k, std::string& slot) {
            const auto it = o.find(k);
            if (it == o.end() || !it->second.isString()) {
                return false;
            }
            slot = it->second.asString("");
            return true;
        };
        if (!get("id", e.id) || !get("category", e.category) || !get("status", e.status)) {
            rep.error = "registry: feature missing id/category/status";
            return rep;
        }
        const auto nit = o.find("notes");
        if (nit != o.end() && nit->second.isString()) {
            e.notes = nit->second.asString("");
        }
        const auto cit = o.find("capabilities");
        if (cit != o.end() && cit->second.isArray()) {
            for (const auto& c : cit->second.asArray(json::Value::emptyArray())) {
                if (c.isString()) {
                    e.capabilities.push_back(c.asString(""));
                }
            }
        }
        const auto rit = o.find("requires");
        if (rit != o.end()) {
            if (!rit->second.isArray()) {
                rep.error = "registry: requires is not an array";
                return rep;
            }
            for (const auto& c : rit->second.asArray(json::Value::emptyArray())) {
                if (c.isString()) {
                    e.requiresCaps.push_back(c.asString(""));
                }
            }
        }
        rep.entries.push_back(std::move(e));
    }
    // Integrity: meta.count must agree with what we actually parsed.
    const auto mit = root.find("meta");
    if (mit != root.end() && mit->second.isObject()) {
        const auto& m = mit->second.asObject(json::Value::emptyObject());
        const auto cit2 = m.find("count");
        if (cit2 != m.end() && cit2->second.isNumber()) {
            const auto declared = static_cast<size_t>(cit2->second.asNumber(0));
            if (declared != rep.entries.size()) {
                rep.error = "registry: meta.count " + std::to_string(declared) + " != " +
                            std::to_string(rep.entries.size()) + " parsed features";
                rep.entries.clear();
                return rep;
            }
            rep.declaredCount = declared;
        }
    }
    rep.ok = true;
    return rep;
}

std::vector<GuiModuleEntry> filterByCategory(const std::vector<GuiModuleEntry>& all,
                                             const std::string& category) {
    std::vector<GuiModuleEntry> out;
    for (const auto& e : all) {
        if (e.category == category) {
            out.push_back(e);
        }
    }
    return out;
}

namespace {

std::string lower(std::string s) {
    for (auto& c : s) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return s;
}

} // namespace

std::vector<GuiModuleEntry> search(const std::vector<GuiModuleEntry>& all,
                                    const std::string& query) {
    const std::string q = lower(query);
    std::vector<GuiModuleEntry> out;
    for (const auto& e : all) {
        if (lower(e.id).find(q) != std::string::npos
            || lower(e.notes).find(q) != std::string::npos) {
            out.push_back(e);
        }
    }
    return out;
}

std::vector<GuiModuleEntry> onlyOperable(const std::vector<GuiModuleEntry>& all) {
    std::vector<GuiModuleEntry> out;
    for (const auto& e : all) {
        if (e.operable()) {
            out.push_back(e);
        }
    }
    return out;
}

} // namespace xykell::gui
