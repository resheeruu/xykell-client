#include "xykell/module_manager.h"

namespace xykell {

bool ModuleManager::registerModule(ModuleDescriptor desc) {
    if (desc.id.empty() || modules_.count(desc.id) != 0) {
        return false;
    }
    modules_.emplace(desc.id, std::move(desc));
    return true;
}

bool ModuleManager::unregister(const std::string& id) {
    return modules_.erase(id) != 0;
}

bool ModuleManager::setEnabled(const std::string& id, bool on) {
    const auto it = modules_.find(id);
    if (it == modules_.end() || it->second.state == ModuleState::Quarantined) {
        return false;
    }
    it->second.state = on ? ModuleState::Enabled : ModuleState::Loaded;
    return true;
}

void ModuleManager::quarantine(const std::string& id, const std::string& reason) {
    const auto it = modules_.find(id);
    if (it == modules_.end()) {
        return;
    }
    it->second.state = ModuleState::Quarantined;
    it->second.quarantineReason = reason;
}

const ModuleDescriptor* ModuleManager::get(const std::string& id) const {
    const auto it = modules_.find(id);
    return it == modules_.end() ? nullptr : &it->second;
}

std::vector<ModuleDescriptor> ModuleManager::list() const {
    std::vector<ModuleDescriptor> out;
    out.reserve(modules_.size());
    for (const auto& [id, desc] : modules_) {
        out.push_back(desc);
    }
    return out;
}

} // namespace xykell
