#include "xykell/load_stages.h"

namespace xykell {

std::string stageName(LoadStage s) {
    switch (s) {
        case LoadStage::ProcessStarted: return "PROCESS_STARTED";
        case LoadStage::NativeLibraryLoaded: return "NATIVE_LIBRARY_LOADED";
        case LoadStage::ModRegistered: return "MOD_REGISTERED";
        case LoadStage::CoreInitialized: return "CORE_INITIALIZED";
        case LoadStage::ConfigInitialized: return "CONFIG_INITIALIZED";
        case LoadStage::RegistryInitialized: return "REGISTRY_INITIALIZED";
        case LoadStage::DiagnosticsInitialized: return "DIAGNOSTICS_INITIALIZED";
        case LoadStage::HudInitialized: return "HUD_INITIALIZED";
        case LoadStage::InputInitialized: return "INPUT_INITIALIZED";
        case LoadStage::RuntimeProbeStarted: return "RUNTIME_PROBE_STARTED";
        case LoadStage::RuntimeProbeCompleted: return "RUNTIME_PROBE_COMPLETED";
        case LoadStage::Ready: return "READY";
    }
    return "?";
}

void LoadTracker::mark(LoadStage stage, const std::string& component) {
    history_.push_back(Checkpoint{stage, true, component, {}, {}, {}});
}

void LoadTracker::fail(LoadStage stage, const std::string& component,
                       const std::string& expected, const std::string& observed,
                       const std::string& error) {
    failure_ = Checkpoint{stage, false, component, expected, observed, error};
    history_.push_back(failure_);
    failed_ = true;
}

LoadStage LoadTracker::lastStage() const {
    return history_.empty() ? LoadStage::ProcessStarted : history_.back().stage;
}

std::string LoadTracker::report() const {
    if (!failed_) {
        return "load: " + stageName(lastStage()) + " (no failure)";
    }
    const auto& f = failure_;
    return "stage: " + stageName(f.stage) + "\ncomponent: " + f.component
         + "\nexpected: " + f.expected + "\nobserved: " + f.observed
         + "\nerror: " + f.error + "\nrecovery: see CrashGuard safe mode";
}

} // namespace xykell
