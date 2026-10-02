#pragma once

// Persistent crash guard. Tracks per-module crash counts, quarantine that
// survives restarts, safe-mode flag with a visible reason, and the
// last-known-good profile name. Independent of any game hooks: it only
// records what the client itself reports. State file: <root>/crashguard.json.
#include <map>
#include <string>
#include <vector>

namespace xykell {

class CrashGuard {
  public:
    static constexpr int kQuarantineThreshold = 3;

    explicit CrashGuard(std::string rootDir) : root_(std::move(rootDir)) {}

    bool load(std::string& error);
    bool save(std::string& error) const;

    // Records a crash; auto-quarantines at threshold. Returns true when this
    // crash crossed the threshold (caller should disable + persist).
    bool recordCrash(const std::string& moduleId, const std::string& reason);
    int crashCount(const std::string& moduleId) const;

    void quarantineModule(const std::string& moduleId, const std::string& reason);
    void clearQuarantine(const std::string& moduleId);
    bool isQuarantined(const std::string& moduleId) const;
    std::vector<std::string> quarantined() const;
    std::string quarantineReason(const std::string& moduleId) const;

    bool isSafeMode() const { return safeMode_; }
    void enterSafeMode(const std::string& reason);
    void exitSafeMode();
    const std::string& safeModeReason() const { return safeReason_; }

    void setLastKnownGood(const std::string& profile) { lastGood_ = profile; }
    const std::string& lastKnownGood() const { return lastGood_; }

    // Deterministic startup report for the launcher UI.
    std::string safeModeReport(std::size_t& disabledCount) const;

  private:
    std::string root_;
    std::map<std::string, int> crashes_;
    std::map<std::string, std::string> quarantine_;
    bool safeMode_ = false;
    std::string safeReason_;
    std::string lastGood_ = "Default";

    std::string path() const { return root_ + "/crashguard.json"; }
};

} // namespace xykell
