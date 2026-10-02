#pragma once

// Deterministic load checkpoints (§3). Marks each load stage in order; on
// failure the report names the exact stage/component/expected/observed, so a
// device run identifies the failing boundary instead of "doesn't work".
// Pure C++ (host-testable); wired into XykellMod::load().
#include <string>
#include <vector>

namespace xykell {

enum class LoadStage {
    ProcessStarted = 0,
    NativeLibraryLoaded,
    ModRegistered,
    CoreInitialized,
    ConfigInitialized,
    RegistryInitialized,
    DiagnosticsInitialized,
    HudInitialized,
    InputInitialized,
    RuntimeProbeStarted,
    RuntimeProbeCompleted,
    Ready,
};

std::string stageName(LoadStage s);

struct Checkpoint {
    LoadStage stage = LoadStage::ProcessStarted;
    bool ok = true;
    std::string component;
    std::string expected;
    std::string observed;
    std::string error;
};

class LoadTracker {
  public:
    void mark(LoadStage stage, const std::string& component = {});
    void fail(LoadStage stage, const std::string& component,
              const std::string& expected, const std::string& observed,
              const std::string& error);

    bool failed() const { return failed_; }
    LoadStage lastStage() const;
    const Checkpoint& failure() const { return failure_; }
    const std::vector<Checkpoint>& history() const { return history_; }
    std::string report() const;

  private:
    std::vector<Checkpoint> history_;
    Checkpoint failure_;
    bool failed_ = false;
};

} // namespace xykell
