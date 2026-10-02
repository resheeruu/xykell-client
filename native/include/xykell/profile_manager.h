#pragma once

// Profile system: named configuration presets (module states/settings, HUD
// layout, GUI settings, theme, input binds, version metadata). Stored as
// schema-versioned JSON files under <root>/profiles/<name>.json; the active
// profile name lives in <root>/active.profile. Presets only — a profile never
// implies its modules are supported (see registry statuses).
#include <map>
#include <string>
#include <vector>

#include "xykell/json_min.h"

namespace xykell {

struct Profile {
    static constexpr int kSchemaVersion = 1;

    std::string name = "Default";
    std::map<std::string, bool> modules; // id -> enabled
    json::Value settings = json::Value(json::Object{});
    json::Value hudLayout = json::Value(json::Object{});
    json::Value gui = json::Value(json::Object{});
    std::string theme = "Xykell Dark";
    json::Value input = json::Value(json::Object{});
    json::Value versionMeta = json::Value(json::Object{});

    json::Value serialize() const;
    // Returns false (leaving *this untouched) on schema mismatch/corruption.
    bool deserialize(const json::Value& v, std::string& error);
};

class ProfileManager {
  public:
    static const std::vector<std::string>& builtinNames();

    explicit ProfileManager(std::string rootDir) : root_(std::move(rootDir)) {}

    std::vector<std::string> list() const;
    bool exists(const std::string& name) const;
    bool create(const std::string& name, std::string& error); // from Default
    bool duplicate(const std::string& from, const std::string& to, std::string& error);
    bool rename(const std::string& from, const std::string& to, std::string& error);
    bool remove(const std::string& name, std::string& error); // never Default
    bool reset(const std::string& name, std::string& error);  // back to builtin
    bool load(const std::string& name, Profile& out, std::string& error) const;
    bool save(const Profile& p, std::string& error) const;
    bool exportTo(const std::string& name, const std::string& destPath,
                  std::string& error) const;
    bool importFrom(const std::string& srcPath, const std::string& name,
                    std::string& error);

    std::string active() const;
    bool setActive(const std::string& name, std::string& error);

  private:
    std::string root_;
    std::string pathFor(const std::string& name) const;
    static bool validName(const std::string& name);
};

} // namespace xykell
