#pragma once

// ClickGUI data model. Entries are built ONLY by parsing registry/features.json
// (buildFromRegistryJson) — there is no second hardcoded catalog; the unit
// test parses the real file and asserts the count matches. Rendering/input
// binding comes later; this layer owns navigation, search, favorites,
// compatibility presentation, and settings shape.
#include <string>
#include <vector>

#include "xykell/json_min.h"

namespace xykell::gui {

struct GuiModuleEntry {
    std::string id;
    std::string category;
    std::string status; // SUPPORTED/PARTIAL/... as written in the registry
    std::vector<std::string> capabilities;
    std::vector<std::string> requiresCaps; // runtime capabilities (Batch 4)
    std::string notes;
    bool favorite = false;

    // Operable = user may toggle it without pretending. Only states with at
    // least build-level reality qualify; everything else is display-only.
    bool operable() const { return status == "SUPPORTED" || status == "PARTIAL"; }
};

struct ParseReport {
    std::vector<GuiModuleEntry> entries;
    bool ok = false;
    std::string error;
    // meta.count from the registry, or 0 when absent. Parsing fails when this
    // is present and disagrees with entries.size(), so a truncated or
    // hand-edited registry cannot silently load a partial catalog.
    size_t declaredCount = 0;
};

ParseReport buildFromRegistryJson(const std::string& jsonText);

std::vector<GuiModuleEntry> filterByCategory(const std::vector<GuiModuleEntry>& all,
                                             const std::string& category);
std::vector<GuiModuleEntry> search(const std::vector<GuiModuleEntry>& all,
                                    const std::string& query);
std::vector<GuiModuleEntry> onlyOperable(const std::vector<GuiModuleEntry>& all);

} // namespace xykell::gui
