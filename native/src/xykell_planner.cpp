#include "xykell/planner.h"

#include <cctype>

namespace xykell::ai {
namespace {

std::string lower(std::string s) {
    for (auto& c : s) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return s;
}

} // namespace

Plan planForGoal(const std::string& goal, const gui::GuiController& gui,
                 const runtime::ProbeReport& probe, const ModuleManager& mods) {
    Plan plan;
    const std::string q = lower(goal);
    if (q.empty()) {
        plan.summary = "empty goal: no steps proposed";
        return plan;
    }
    for (const auto& e : gui.visible()) {
        const std::string hay = lower(e.id + " " + e.category + " " + e.notes);
        bool hit = false;
        std::string word;
        for (const char c : q + " ") {
            if (c == ' ' || c == ',') {
                if (word.size() > 2 && hay.find(word) != std::string::npos) {
                    hit = true;
                    break;
                }
                word.clear();
            } else {
                word.push_back(c);
            }
        }
        if (!hit) {
            continue;
        }
        PlanStep step;
        step.moduleId = e.id;
        step.availability = gui.availability(e, probe, mods);
        if (step.availability == "AVAILABLE") {
            step.reason = "requirements met";
        } else {
            step.reason = "requires: ";
            bool first = true;
            for (const auto& r : e.requiresCaps) {
                if (!first) {
                    step.reason += ", ";
                }
                first = false;
                step.reason += r + "=" + runtime::stateName(probe.stateOf(r));
            }
            if (e.requiresCaps.empty()) {
                step.reason += "(none — see registry status " + e.status + ")";
            }
        }
        plan.steps.push_back(std::move(step));
    }
    plan.executable = !plan.steps.empty();
    for (const auto& s : plan.steps) {
        if (s.availability != "AVAILABLE") {
            plan.executable = false;
        }
    }
    plan.summary = plan.steps.empty()
                       ? "no registry entries match this goal"
                       : (plan.executable ? "all steps available"
                                          : "plan blocked: see step reasons");
    return plan;
}

bool planIsSafe(const Plan& plan) {
    for (const auto& s : plan.steps) {
        if (s.availability == "QUARANTINED" || s.availability == "BLOCKED") {
            return false;
        }
    }
    return true;
}

} // namespace xykell::ai
