#include "xykell/config_store.h"

#include <chrono>

#include "xykell/file_util.h"

namespace xykell {

XykellConfig::XykellConfig() : root_(defaults()) {}

json::Value XykellConfig::defaults() {
    json::Object r;
    r.emplace("schemaVersion", json::Value(1));
    auto section = []() { return json::Value(json::Object{}); };
    r.emplace("client", section());
    r.emplace("modules", section());
    r.emplace("hud", section());
    r.emplace("gui", section());
    r.emplace("rendering", section());
    r.emplace("input", section());
    r.emplace("network", section());
    json::Object profile;
    profile.emplace("active", json::Value("Default"));
    r.emplace("profile", json::Value(std::move(profile)));
    return json::Value(std::move(r));
}

namespace {

const json::Object* sectionObj(const json::Value& root, const char* section) {
    if (!root.isObject()) {
        return nullptr;
    }
    const auto& obj = root.asObject(json::Value::emptyObject());
    const auto it = obj.find(section);
    if (it == obj.end() || !it->second.isObject()) {
        return nullptr;
    }
    return &it->second.asObject(json::Value::emptyObject());
}

json::Object* mutableSection(json::Value& root, const char* section) {
    if (!root.isObject()) {
        return nullptr;
    }
    auto& obj = const_cast<json::Object&>(root.asObject(json::Value::emptyObject()));
    auto it = obj.find(section);
    if (it == obj.end()) {
        it = obj.emplace(section, json::Value(json::Object{})).first;
    }
    if (!it->second.isObject()) {
        it->second = json::Value(json::Object{});
    }
    return const_cast<json::Object*>(&it->second.asObject(json::Value::emptyObject()));
}

} // namespace

bool XykellConfig::load(const std::string& path) {
    recovered_ = false;
    migratedFrom_ = -1;
    lastError_.clear();
    const auto read = fs::readFile(path);
    if (!read.ok) {
        root_ = defaults(); // missing file: clean defaults, not an error
        return true;
    }
    const auto parsed = json::parse(read.content);
    if (!parsed.ok) {
        lastError_ = parsed.error;
    } else {
        const auto& obj = parsed.value.asObject(json::Value::emptyObject());
        const auto it = obj.find("schemaVersion");
        const int ver = (it != obj.end()) ? static_cast<int>(it->second.asNumber(-1)) : -1;
        if (ver < 1 || ver > kSchemaVersion) {
            lastError_ = "unsupported schemaVersion";
        } else {
            root_ = parsed.value;
            if (ver < kSchemaVersion && migrate(ver)) {
                migratedFrom_ = ver;
            }
            return true;
        }
    }
    // Corrupt: back up the bad file, fall back to defaults.
    const auto stamp = std::chrono::system_clock::now().time_since_epoch().count();
    const std::string backup = path + ".corrupt." + std::to_string(stamp);
    std::string err;
    const auto slash = path.find_last_of("/\\");
    if (slash != std::string::npos) {
        std::string ignored;
        fs::ensureDir(path.substr(0, slash), ignored);
    }
    if (!fs::atomicWrite(backup, read.content, err)) {
        lastError_ += " (backup also failed: " + err + ")";
    }
    root_ = defaults();
    recovered_ = true;
    return false;
}

bool XykellConfig::save(const std::string& path) const {
    std::string err;
    const auto slash = path.find_last_of("/\\");
    if (slash != std::string::npos && !fs::ensureDir(path.substr(0, slash), err)) {
        return false;
    }
    if (!fs::atomicWrite(path, json::stringify(root_), err)) {
        return false;
    }
    return true;
}

bool XykellConfig::migrate(int from) {
    (void)from;
    // v1 is current: fill any missing top-level sections from defaults.
    if (!root_.isObject()) {
        return false;
    }
    const auto fresh = defaults().asObject(json::Value::emptyObject());
    auto& target = const_cast<json::Object&>(root_.asObject(json::Value::emptyObject()));
    for (const auto& [k, v] : fresh) {
        if (target.find(k) == target.end()) {
            target.emplace(k, v);
        }
    }
    target["schemaVersion"] = json::Value(kSchemaVersion);
    return true;
}

bool XykellConfig::moduleEnabled(const std::string& id, bool fallback) const {
    const auto* mods = sectionObj(root_, "modules");
    if (mods == nullptr) {
        return fallback;
    }
    const auto it = mods->find(id);
    if (it == mods->end() || !it->second.isObject()) {
        return fallback;
    }
    const auto& m = it->second.asObject(json::Value::emptyObject());
    const auto eit = m.find("enabled");
    return (eit != m.end() && eit->second.isBool()) ? eit->second.asBool() : fallback;
}

void XykellConfig::setModuleEnabled(const std::string& id, bool on) {
    auto* mods = mutableSection(root_, "modules");
    if (mods == nullptr) {
        return;
    }
    auto it = mods->find(id);
    if (it == mods->end() || !it->second.isObject()) {
        json::Object m;
        m.emplace("enabled", json::Value(on));
        (*mods)[id] = json::Value(std::move(m));
        return;
    }
    auto& m = const_cast<json::Object&>(it->second.asObject(json::Value::emptyObject()));
    m["enabled"] = json::Value(on);
}

std::string XykellConfig::getString(const std::string& section, const std::string& key,
                                    const std::string& fallback) const {
    const auto* s = sectionObj(root_, section.c_str());
    if (s == nullptr) {
        return fallback;
    }
    const auto it = s->find(key);
    if (it == s->end() || !it->second.isString()) {
        return fallback; // malformed value: safe fallback, no crash
    }
    return it->second.asString(fallback);
}

void XykellConfig::setString(const std::string& section, const std::string& key,
                             const std::string& value) {
    auto* s = mutableSection(root_, section.c_str());
    if (s != nullptr) {
        (*s)[key] = json::Value(value);
    }
}

} // namespace xykell
