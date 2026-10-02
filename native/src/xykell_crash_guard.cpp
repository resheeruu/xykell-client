#include "xykell/crash_guard.h"

#include <type_traits>

#include "xykell/file_util.h"
#include "xykell/json_min.h"

namespace xykell {

bool CrashGuard::load(std::string& error) {
    const auto read = fs::readFile(path());
    if (!read.ok) {
        return true; // first run: clean defaults
    }
    const auto parsed = json::parse(read.content);
    if (!parsed.ok || !parsed.value.isObject()) {
        error = "crashguard state corrupt: " + parsed.error;
        return false; // caller decides: stay in safe mode, don't guess
    }
    const auto& o = parsed.value.asObject(json::Value::emptyObject());
    crashes_.clear();
    quarantine_.clear();
    const auto getObj = [&](const char* key, auto& slot, bool valuesAreStrings) {
        const auto it = o.find(key);
        if (it == o.end()) {
            return true;
        }
        if (!it->second.isObject()) {
            error = std::string(key) + " is not an object";
            return false;
        }
        for (const auto& [k, v] : it->second.asObject(json::Value::emptyObject())) {
            if constexpr (std::is_same_v<typename std::decay_t<decltype(slot)>::mapped_type,
                                          std::string>) {
                (void)valuesAreStrings;
                if (!v.isString()) {
                    error = "quarantine reason not a string";
                    return false;
                }
                slot[k] = v.asString("");
            } else {
                (void)valuesAreStrings;
                if (!v.isNumber()) {
                    error = "crash count not a number";
                    return false;
                }
                slot[k] = static_cast<int>(v.asNumber());
            }
        }
        return true;
    };
    if (!getObj("crashes", crashes_, false) || !getObj("quarantine", quarantine_, true)) {
        return false;
    }
    const auto sm = o.find("safeMode");
    safeMode_ = (sm != o.end() && sm->second.isBool() && sm->second.asBool());
    const auto sr = o.find("safeReason");
    safeReason_ = (sr != o.end() && sr->second.isString()) ? sr->second.asString("") : "";
    const auto lg = o.find("lastGood");
    lastGood_ = (lg != o.end() && lg->second.isString()) ? lg->second.asString("Default")
                                                         : "Default";
    return true;
}

bool CrashGuard::save(std::string& error) const {
    if (!fs::ensureDir(root_, error)) {
        return false;
    }    json::Object crashes, quar;
    for (const auto& [k, v] : crashes_) {
        crashes.emplace(k, json::Value(v));
    }
    for (const auto& [k, v] : quarantine_) {
        quar.emplace(k, json::Value(v));
    }
    json::Object r;
    r.emplace("schemaVersion", json::Value(1));
    r.emplace("crashes", json::Value(std::move(crashes)));
    r.emplace("quarantine", json::Value(std::move(quar)));
    r.emplace("safeMode", json::Value(safeMode_));
    r.emplace("safeReason", json::Value(safeReason_));
    r.emplace("lastGood", json::Value(lastGood_));
    return fs::atomicWrite(path(), json::stringify(json::Value(std::move(r))), error);
}

bool CrashGuard::recordCrash(const std::string& moduleId, const std::string& reason) {
    const int n = ++crashes_[moduleId];
    if (n >= kQuarantineThreshold && !isQuarantined(moduleId)) {
        quarantineModule(moduleId, reason + " (auto: crash x" + std::to_string(n) + ")");
        return true;
    }
    return false;
}

int CrashGuard::crashCount(const std::string& moduleId) const {
    const auto it = crashes_.find(moduleId);
    return it == crashes_.end() ? 0 : it->second;
}

void CrashGuard::quarantineModule(const std::string& moduleId, const std::string& reason) {
    quarantine_[moduleId] = reason;
}

void CrashGuard::clearQuarantine(const std::string& moduleId) {
    quarantine_.erase(moduleId);
    crashes_.erase(moduleId);
}

bool CrashGuard::isQuarantined(const std::string& moduleId) const {
    return quarantine_.count(moduleId) != 0;
}

std::vector<std::string> CrashGuard::quarantined() const {
    std::vector<std::string> out;
    for (const auto& [k, v] : quarantine_) {
        (void)v;
        out.push_back(k);
    }
    return out;
}

std::string CrashGuard::quarantineReason(const std::string& moduleId) const {
    const auto it = quarantine_.find(moduleId);
    return it == quarantine_.end() ? "" : it->second;
}

void CrashGuard::enterSafeMode(const std::string& reason) {
    safeMode_ = true;
    safeReason_ = reason;
}

void CrashGuard::exitSafeMode() {
    safeMode_ = false;
    safeReason_.clear();
}

std::string CrashGuard::safeModeReport(std::size_t& disabledCount) const {
    disabledCount = quarantine_.size();
    if (!safeMode_) {
        return "SAFE MODE: off";
    }
    std::string r = "SAFE MODE\nReason: " + (safeReason_.empty() ? "(unspecified)" : safeReason_);
    r += "\nDisabled modules: " + std::to_string(disabledCount);
    for (const auto& [id, reason] : quarantine_) {
        r += "\n- " + id + ": " + reason;
    }
    return r;
}

} // namespace xykell
