#include "xykell/profile_manager.h"

#include <filesystem>

#include "xykell/file_util.h"

namespace xykell {
namespace stdfs = std::filesystem;

namespace {

Profile builtin(const std::string& name) {
    Profile p;
    p.name = name;
    return p;
}

bool writeProfileFile(const std::string& path, const Profile& p, std::string& error) {
    return fs::atomicWrite(path, json::stringify(p.serialize()), error);
}

bool readProfileFile(const std::string& path, Profile& out, std::string& error) {
    const auto read = fs::readFile(path);
    if (!read.ok) {
        error = "cannot read profile: " + read.error;
        return false;
    }
    const auto parsed = json::parse(read.content);
    if (!parsed.ok) {
        error = "profile JSON corrupt: " + parsed.error;
        return false;
    }
    return out.deserialize(parsed.value, error);
}

} // namespace

json::Value Profile::serialize() const {
    json::Object r;
    r.emplace("schemaVersion", json::Value(kSchemaVersion));
    r.emplace("name", json::Value(name));
    json::Object mods;
    for (const auto& [id, on] : modules) {
        mods.emplace(id, json::Value(on));
    }
    r.emplace("modules", json::Value(std::move(mods)));
    r.emplace("settings", settings);
    r.emplace("hudLayout", hudLayout);
    r.emplace("gui", gui);
    r.emplace("theme", json::Value(theme));
    r.emplace("input", input);
    r.emplace("versionMeta", versionMeta);
    return json::Value(std::move(r));
}

bool Profile::deserialize(const json::Value& v, std::string& error) {
    if (!v.isObject()) {
        error = "profile is not an object";
        return false;
    }
    const auto& o = v.asObject(json::Value::emptyObject());
    const auto it = o.find("schemaVersion");
    if (it == o.end() || static_cast<int>(it->second.asNumber(-1)) != kSchemaVersion) {
        error = "unsupported profile schemaVersion";
        return false;
    }
    Profile tmp;
    const auto nameIt = o.find("name");
    if (nameIt != o.end() && nameIt->second.isString()) {
        tmp.name = nameIt->second.asString(tmp.name);
    }
    const auto modsIt = o.find("modules");
    if (modsIt != o.end()) {
        if (!modsIt->second.isObject()) {
            error = "modules is not an object";
            return false;
        }
        for (const auto& [id, val] : modsIt->second.asObject(json::Value::emptyObject())) {
            if (!val.isBool()) {
                error = "module state is not a bool: " + id;
                return false; // malformed value: reject, don't guess
            }
            tmp.modules[id] = val.asBool();
        }
    }
    const auto getObj = [&](const char* key, json::Value& slot) {
        const auto f = o.find(key);
        if (f != o.end()) {
            if (!f->second.isObject()) {
                error = std::string(key) + " is not an object";
                return false;
            }
            slot = f->second;
        }
        return true;
    };
    if (!getObj("settings", tmp.settings) || !getObj("hudLayout", tmp.hudLayout)
        || !getObj("gui", tmp.gui) || !getObj("input", tmp.input)
        || !getObj("versionMeta", tmp.versionMeta)) {
        return false;
    }
    const auto themeIt = o.find("theme");
    if (themeIt != o.end()) {
        if (!themeIt->second.isString()) {
            error = "theme is not a string";
            return false;
        }
        tmp.theme = themeIt->second.asString(tmp.theme);
    }
    *this = std::move(tmp);
    return true;
}

const std::vector<std::string>& ProfileManager::builtinNames() {
    static const std::vector<std::string> names = {"Default", "PvP",     "Survival",
                                                   "Performance", "Builder", "Minimal"};
    return names;
}

bool ProfileManager::validName(const std::string& name) {
    if (name.empty() || name.size() > 64) {
        return false;
    }
    for (const char c : name) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                     || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == ' ';
        if (!ok) {
            return false;
        }
    }
    return name != "." && name != "..";
}

std::string ProfileManager::pathFor(const std::string& name) const {
    return root_ + "/profiles/" + name + ".json";
}

std::vector<std::string> ProfileManager::list() const {
    std::vector<std::string> out;
    std::error_code ec;
    stdfs::create_directories(root_ + "/profiles", ec);
    for (const auto& e : stdfs::directory_iterator(root_ + "/profiles", ec)) {
        if (e.is_regular_file(ec) && e.path().extension() == ".json") {
            out.push_back(e.path().stem().string());
        }
    }
    return out;
}

bool ProfileManager::exists(const std::string& name) const {
    if (!validName(name)) {
        return false;
    }
    return stdfs::exists(pathFor(name));
}

bool ProfileManager::create(const std::string& name, std::string& error) {
    if (!validName(name)) {
        error = "invalid profile name";
        return false;
    }
    if (exists(name)) {
        error = "profile already exists";
        return false;
    }
    std::string err;
    if (!fs::ensureDir(root_ + "/profiles", err)) {
        error = err;
        return false;
    }
    Profile p = builtin(name);
    // New profiles inherit Default's content when present.
    Profile dflt;
    std::string ignored;
    if (readProfileFile(pathFor("Default"), dflt, ignored)) {
        dflt.name = name;
        p = dflt;
    }
    return writeProfileFile(pathFor(name), p, error);
}

bool ProfileManager::duplicate(const std::string& from, const std::string& to,
                               std::string& error) {
    if (!validName(to)) {
        error = "invalid profile name";
        return false;
    }
    if (exists(to)) {
        error = "target already exists";
        return false;
    }
    Profile p;
    if (!load(from, p, error)) {
        return false;
    }
    p.name = to;
    return writeProfileFile(pathFor(to), p, error);
}

bool ProfileManager::rename(const std::string& from, const std::string& to,
                             std::string& error) {
    if (!validName(to)) {
        error = "invalid profile name";
        return false;
    }
    if (from == "Default" || to == "Default") {
        error = "cannot rename Default";
        return false;
    }
    if (exists(to)) {
        error = "target already exists";
        return false;
    }
    Profile p;
    if (!load(from, p, error)) {
        return false;
    }
    p.name = to;
    if (!writeProfileFile(pathFor(to), p, error)) {
        return false;
    }
    std::error_code ec;
    stdfs::remove(pathFor(from), ec);
    if (active() == from) {
        return setActive(to, error);
    }
    return true;
}

bool ProfileManager::remove(const std::string& name, std::string& error) {
    if (name == "Default") {
        error = "cannot delete Default";
        return false;
    }
    if (!exists(name)) {
        error = "no such profile";
        return false;
    }
    std::error_code ec;
    stdfs::remove(pathFor(name), ec);
    if (ec) {
        error = ec.message();
        return false;
    }
    if (active() == name) {
        return setActive("Default", error);
    }
    return true;
}

bool ProfileManager::reset(const std::string& name, std::string& error) {
    if (!validName(name)) {
        error = "invalid profile name";
        return false;
    }
    std::string err;
    if (!fs::ensureDir(root_ + "/profiles", err)) {
        error = err;
        return false;
    }
    return writeProfileFile(pathFor(name), builtin(name), error);
}

bool ProfileManager::load(const std::string& name, Profile& out, std::string& error) const {
    if (!validName(name) || !exists(name)) {
        error = "no such profile";
        return false;
    }
    return readProfileFile(pathFor(name), out, error);
}

bool ProfileManager::save(const Profile& p, std::string& error) const {
    if (!validName(p.name)) {
        error = "invalid profile name";
        return false;
    }
    std::string err;
    if (!fs::ensureDir(root_ + "/profiles", err)) {
        error = err;
        return false;
    }
    return writeProfileFile(pathFor(p.name), p, error);
}

bool ProfileManager::exportTo(const std::string& name, const std::string& destPath,
                              std::string& error) const {
    Profile p;
    if (!load(name, p, error)) {
        return false;
    }
    return fs::atomicWrite(destPath, json::stringify(p.serialize()), error);
}

bool ProfileManager::importFrom(const std::string& srcPath, const std::string& name,
                                std::string& error) {
    if (!validName(name)) {
        error = "invalid profile name";
        return false;
    }
    Profile p;
    if (!readProfileFile(srcPath, p, error)) {
        return false; // corrupt import rejected, existing untouched
    }
    p.name = name;
    std::string err;
    if (!fs::ensureDir(root_ + "/profiles", err)) {
        error = err;
        return false;
    }
    return writeProfileFile(pathFor(name), p, error);
}

std::string ProfileManager::active() const {
    const auto read = fs::readFile(root_ + "/active.profile");
    if (!read.ok) {
        return "Default";
    }
    std::string name = read.content;
    while (!name.empty()
           && (name.back() == '\n' || name.back() == '\r' || name.back() == ' ')) {
        name.pop_back();
    }
    return validName(name) && exists(name) ? name : "Default";
}

bool ProfileManager::setActive(const std::string& name, std::string& error) {
    if (!exists(name)) {
        // Auto-create builtins on first use so Default always works.
        bool isBuiltin = false;
        for (const auto& b : builtinNames()) {
            if (b == name) {
                isBuiltin = true;
            }
        }
        if (isBuiltin) {
            if (!reset(name, error)) {
                return false;
            }
        } else {
            error = "no such profile";
            return false;
        }
    }
    return fs::atomicWrite(root_ + "/active.profile", name, error);
}

} // namespace xykell
