#include "xykell/gui_controller.h"

namespace xykell::gui {

const std::map<std::string, std::string>& GuiController::implemented() {
    // ONLY entries with real backing behavior. Everything else is refused.
    // client.* -> core menu module state; hud.* -> proof overlay state.
    static const std::map<std::string, std::string> impl = {
        {"client.core", "xykell-core"},
        {"client.config_store", "xykell-core"},
        {"client.version_adapter", "xykell-core"},
        {"hud.watermark", "xykell-hud"},
        {"hud.touch_indicators", "xykell-hud"},
    };
    return impl;
}

bool GuiController::loadRegistry(const std::string& jsonText, std::string& error) {
    if (loaded_) {
        return true;
    }
    const auto rep = buildFromRegistryJson(jsonText);
    if (!rep.ok) {
        error = rep.error;
        return false;
    }
    entries_ = std::move(rep.entries);
    loaded_ = true;
    return true;
}

std::vector<GuiModuleEntry> GuiController::visible() const {
    std::vector<GuiModuleEntry> out = entries_;
    if (category != "all") {
        out = filterByCategory(out, category);
    }
    if (!query.empty()) {
        out = search(out, query);
    }
    return out;
}

ToggleOutcome GuiController::requestToggle(const std::string& registryId,
                                            ModuleManager& mods) {
    const GuiModuleEntry* found = nullptr;
    for (const auto& e : entries_) {
        if (e.id == registryId) {
            found = &e;
            break;
        }
    }
    if (found == nullptr) {
        return ToggleOutcome::UnknownId;
    }
    if (found->status == "RESEARCH_REQUIRED") {
        return ToggleOutcome::RefusedResearch;
    }
    if (found->status == "NOT_IMPLEMENTED") {
        return ToggleOutcome::RefusedNotImplemented;
    }
    if (!found->operable()) {
        // UNSUPPORTED/BLOCKED/anything else: display-only.
        return ToggleOutcome::RefusedNotImplemented;
    }
    const auto it = implemented().find(registryId);
    if (it == implemented().end()) {
        return ToggleOutcome::RefusedNotImplemented;
    }
    const ModuleDescriptor* desc = mods.get(it->second);
    if (desc == nullptr) {
        return ToggleOutcome::UnknownId;
    }
    if (desc->state == ModuleState::Quarantined) {
        return ToggleOutcome::RefusedQuarantined;
    }
    const bool target = (desc->state != ModuleState::Enabled);
    mods.setEnabled(it->second, target);
    return ToggleOutcome::Performed;
}

void GuiController::toggleFavorite(const std::string& id) {
    if (!favorites.erase(id)) {
        favorites.insert(id);
    }
}

bool GuiController::isFavorite(const std::string& id) const {
    return favorites.count(id) != 0;
}

} // namespace xykell::gui
