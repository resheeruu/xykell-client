#include "xykell/runtime_probe.h"

namespace xykell::runtime {

std::string stateName(CapState s) {
    switch (s) {
        case CapState::Verified: return "VERIFIED";
        case CapState::Partial: return "PARTIAL";
        case CapState::ResearchRequired: return "RESEARCH_REQUIRED";
        case CapState::Blocked: return "BLOCKED";
        case CapState::NotImplemented: return "NOT_IMPLEMENTED";
        case CapState::Incompatible: return "INCOMPATIBLE";
    }
    return "?";
}

const Capability* ProbeReport::find(const std::string& id) const {
    for (const auto& c : caps) {
        if (c.id == id) {
            return &c;
        }
    }
    return nullptr;
}

CapState ProbeReport::stateOf(const std::string& id) const {
    const auto* c = find(id);
    return c == nullptr ? CapState::ResearchRequired : c->state;
}

ProbeReport RuntimeProbe::collect(const std::string& xykellVersion,
                                  const std::string& leviPin,
                                  const std::string& preloaderPin) {
    ProbeReport r;
    r.xykellVersion = xykellVersion;
    r.leviPin = leviPin;
    r.preloaderPin = preloaderPin;
#if defined(__aarch64__)
    r.arch = "arm64-v8a";
#else
    r.arch = "unknown-arch";
#endif
    r.minecraftVersion = "unknown"; // no verified runtime source (see docs)
    r.caps = {
        {"LIFECYCLE", CapState::Verified,
         "PL_REGISTER_MOD load/enable/disable/unload (pl/Mod.hpp @0.2.3); M1 builds"},
        {"SHUTDOWN", CapState::Verified, "unload() path; M1 builds"},
        {"CONFIG_DIRS", CapState::Verified,
         "ModContext::configDir()/dataDir()/resourceDir() (pl/Mod.hpp @0.2.3)"},
        {"INPUT_TRANSPORT", CapState::Verified,
         "pl::input::registerTouchCallback (pl/Input.hpp @0.2.3); M1 tap counter builds"},
        {"INPUT_SEMANTICS", CapState::Partial,
         "callback layer (raw Android vs game-translated) unverified; assumed MotionEvent codes"},
        {"MODMENU", CapState::Verified,
         "pl::modmenu::registerModule/ModuleBuilder (pl/ModMenu.hpp @0.2.3); M1 builds"},
        {"OVERLAY_DELIVERY", CapState::Partial,
         "submitDrawCommands builds; delivery tied to unknown render lifecycle"},
        {"FRAME", CapState::ResearchRequired, "no tick/frame callback in pinned SDK"},
        {"PLAYER", CapState::ResearchRequired, "no player API in pinned SDK"},
        {"ENTITY", CapState::ResearchRequired, "no entity API in pinned SDK"},
        {"WORLD", CapState::ResearchRequired, "no world/block/chunk API in pinned SDK"},
        {"CAMERA", CapState::ResearchRequired, "no camera API in pinned SDK"},
        {"RENDER", CapState::Blocked,
         "hook/patch/signature mechanism exists (pl/memory/*) but no verified target; no render callback"},
        {"PACKET", CapState::Blocked, "no packet API in pinned SDK headers"},
        {"VERSION_STRING", CapState::ResearchRequired,
         "device has MC 1.26.45.1 (APK pool evidence) but no in-process source"},
        {"VERSION_POLICY", CapState::Partial,
         "Levi floor >=1.21.80 + v1.5.25 1.26.50 line (changelog evidence)"},
        {"CONFIG_STORE", CapState::Partial,
         "file logic unit-tested; device paths pending first Levi load"},
        {"PROFILE_STORE", CapState::Partial, "same as CONFIG_STORE"},
        {"CRASHGUARD", CapState::Partial, "persistence unit-tested; device pending"},
        {"SCRIPTING", CapState::Partial,
         "app-side runtime/sandbox/api/manager implemented + unit-tested; "
         "in-game script bridge pending device"},
        {"PROXY", CapState::NotImplemented,
         "no packet API in pinned SDK headers (GAP-ANALYSIS); proxy ids are "
         "REFERENCE_ONLY"},
    };
    return r;
}

GateResult canEnable(const std::vector<std::string>& needed,
                     const ProbeReport& probe, bool quarantined) {    if (quarantined) {
        return {false, "quarantined (explicit clear required)"};
    }
    for (const auto& id : needed) {
        const CapState s = probe.stateOf(id);
        if (s != CapState::Verified && s != CapState::Partial) {
            return {false, id + " is " + stateName(s)};
        }
    }
    return {true, "all requirements Verified/Partial"};
}

std::string formatProbeReport(const ProbeReport& probe) {
    std::string out = "XYKELL " + probe.xykellVersion + " | Levi " + probe.leviPin
                    + " | preloader " + probe.preloaderPin + " | mc="
                    + probe.minecraftVersion + " | " + probe.arch + "\n";
    for (const auto& c : probe.caps) {
        out += c.id + "=" + stateName(c.state) + "\n";
    }
    return out;
}

} // namespace xykell::runtime
