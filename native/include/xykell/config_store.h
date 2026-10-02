#pragma once

// Persistent client configuration. Schema-versioned JSON object with fixed
// top-level sections (client/modules/hud/gui/rendering/input/network/profile).
// Load: missing file -> defaults; unparseable/wrong-schema -> backs the bad
// file up as <path>.corrupt and loads defaults (never throws, never crashes).
// Save: atomic write. Migration: versioned upgraders fill new keys.
#include <string>

#include "xykell/json_min.h"

namespace xykell {

class XykellConfig {
  public:
    static constexpr int kSchemaVersion = 1;
    static constexpr const char* kFileName = "xykell.json";

    XykellConfig();

    // Loads path (defaults when missing). Returns true when the file was
    // usable; false when defaults were used (check lastError()/recovered()).
    bool load(const std::string& path);
    bool save(const std::string& path) const;

    bool recovered() const { return recovered_; }
    int migratedFrom() const { return migratedFrom_; } // -1 when no migration
    const std::string& lastError() const { return lastError_; }

    json::Value& root() { return root_; }
    const json::Value& root() const { return root_; }

    // Typed section access with safe fallbacks.
    bool moduleEnabled(const std::string& id, bool fallback) const;
    void setModuleEnabled(const std::string& id, bool on);
    std::string getString(const std::string& section, const std::string& key,
                          const std::string& fallback) const;
    void setString(const std::string& section, const std::string& key,
                   const std::string& value);

    static json::Value defaults();

  private:
    json::Value root_;
    bool recovered_ = false;
    int migratedFrom_ = -1;
    std::string lastError_;

    bool migrate(int from);
};

} // namespace xykell
