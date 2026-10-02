#pragma once

// ClickGUI runtime controller: navigation/search/favorites state over the
// cached registry model, plus honest toggle semantics. A toggle is Performed
// only for operable entries WITH a real backing implementation; everything
// else is refused with a specific reason (never silently, never faked).
#include <map>
#include <set>
#include <string>
#include <vector>

#include "xykell/clickgui_model.h"
#include "xykell/module_manager.h"
#include "xykell/runtime_probe.h"

namespace xykell::gui {

enum class ToggleOutcome {
    Performed,
    RefusedResearch,
    RefusedNotImplemented,
    RefusedQuarantined,
    UnknownId,
};

class GuiController {
  public:
    bool open = false;
    std::string category = "all";
    std::string query;
    std::set<std::string> favorites;

    // Parses once and caches; second call is a no-op returning true.
    bool loadRegistry(const std::string& jsonText, std::string& error);
    bool loaded() const { return loaded_; }
    std::size_t count() const { return entries_.size(); }

    std::vector<GuiModuleEntry> visible() const;
    ToggleOutcome requestToggle(const std::string& registryId, ModuleManager& mods);

    // Runtime availability view (Batch 4). Registry `status` semantics are
    // unchanged; this maps status + gate + quarantine to a display state.
    // AVAILABLE only when operable AND gate-allowed AND not quarantined.
    std::string availability(const GuiModuleEntry& e, const runtime::ProbeReport& probe,
                             const ModuleManager& mods) const;

    void toggleFavorite(const std::string& id);
    bool isFavorite(const std::string& id) const;

  private:
    bool loaded_ = false;
    std::vector<GuiModuleEntry> entries_;

    // Registry id -> runtime ModuleManager id for entries WITH implementations.
    static const std::map<std::string, std::string>& implemented();
};

} // namespace xykell::gui
