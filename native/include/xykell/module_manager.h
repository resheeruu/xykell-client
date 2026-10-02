#pragma once

#include <string>
#include <unordered_map>
#include <vector>

// Runtime module registry. Pure C++. Tracks descriptors + lifecycle state;
// quarantine records WHY a module was disabled without crashing anything.
// (Hook into EventBus failure counts is future work — see GAP-ANALYSIS.)
namespace xykell {

enum class ModuleState {
    Loaded,
    Enabled,
    Quarantined,
};

struct ModuleDescriptor {
    ModuleDescriptor() = default;
    ModuleDescriptor(std::string i, std::string n, std::string c)
        : id(std::move(i)), name(std::move(n)), category(std::move(c)) {}
    std::string id;
    std::string name;
    std::string category;
    ModuleState state = ModuleState::Loaded;
    std::string quarantineReason;
};

class ModuleManager {
  public:
    bool registerModule(ModuleDescriptor desc); // false on duplicate id
    bool unregister(const std::string& id);
    bool setEnabled(const std::string& id, bool on); // false if unknown/quarantined
    void quarantine(const std::string& id, const std::string& reason);

    const ModuleDescriptor* get(const std::string& id) const;
    std::vector<ModuleDescriptor> list() const;

  private:
    std::unordered_map<std::string, ModuleDescriptor> modules_;
};

} // namespace xykell
