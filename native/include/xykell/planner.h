#pragma once

// AI planner kernel (first step of docs/AI-SYSTEM.md). NOT a chatbot and NOT
// an LLM: a deterministic planner grounding natural-language goal keywords in
// the capability system. Every proposed step carries its live availability;
// the plan is executable only when all steps are AVAILABLE. Anything else is
// reported with reasons — the AI never invents capabilities.
#include <string>
#include <vector>

#include "xykell/gui_controller.h"
#include "xykell/module_manager.h"
#include "xykell/runtime_probe.h"

namespace xykell::ai {

struct PlanStep {
    std::string moduleId;
    std::string availability; // AVAILABLE / UNAVAILABLE / BLOCKED / ...
    std::string reason;       // requires-list or refusal explanation
};

struct Plan {
    std::vector<PlanStep> steps;
    bool executable = false;
    std::string summary;
};

// Matches goal keywords (case-insensitive substring) against registry
// id/name/notes. Empty match = empty, non-executable plan (honest).
Plan planForGoal(const std::string& goal, const gui::GuiController& gui,
                 const runtime::ProbeReport& probe, const ModuleManager& mods);

// Safety gate: STOP/PAUSE/CANCEL are always available; anything touching a
// quarantined module or a BLOCKED capability is refused with reasons.
bool planIsSafe(const Plan& plan);

} // namespace xykell::ai
