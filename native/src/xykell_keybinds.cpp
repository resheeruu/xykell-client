// Keybind manager implementation. Pure logic over the descriptor map;
// conflicts are refused with a reason, never silently overwritten.
#include "xykell/keybinds.h"

namespace xykell::input {

bool KeybindManager::registerAction(const std::string& action) {
    if (action.empty()) {
        lastError_ = "empty action id";
        return false;
    }
    auto it = binds_.find(action);
    if (it != binds_.end()) {
        return true;  // idempotent: description kept, binds kept
    }
    Keybind b;
    b.action = action;
    binds_.emplace(action, b);
    lastError_.clear();
    return true;
}

bool KeybindManager::bind(const std::string& action, int code, bool secondary) {
    if (code == 0) {
        lastError_ = "code 0 means unbound";
        return false;
    }
    auto it = binds_.find(action);
    if (it == binds_.end()) {
        lastError_ = "unknown action: " + action;
        return false;
    }
    for (const auto& [id, b] : binds_) {
        if (id != action && (b.primary == code || b.secondary == code)) {
            lastError_ = "code already bound to " + id;
            return false;
        }
    }
    if (secondary) {
        it->second.secondary = code;
    } else {
        it->second.primary = code;
    }
    lastError_.clear();
    return true;
}

bool KeybindManager::unbind(const std::string& action, bool secondary) {
    auto it = binds_.find(action);
    if (it == binds_.end()) {
        lastError_ = "unknown action: " + action;
        return false;
    }
    if (secondary) {
        it->second.secondary = 0;
    } else {
        it->second.primary = 0;
    }
    lastError_.clear();
    return true;
}

std::vector<std::string> KeybindManager::press(int code) const {
    std::vector<std::string> fired;
    if (code == 0) {
        return fired;
    }
    for (const auto& [id, b] : binds_) {
        if (b.primary == code || b.secondary == code) {
            fired.push_back(id);
        }
    }
    return fired;
}

std::vector<int> KeybindManager::boundCodes() const {
    std::vector<int> out;
    for (const auto& [id, b] : binds_) {
        if (b.primary != 0) {
            out.push_back(b.primary);
        }
        if (b.secondary != 0) {
            out.push_back(b.secondary);
        }
    }
    return out;
}

bool KeybindManager::isBound(const std::string& action) const {
    const auto it = binds_.find(action);
    return it != binds_.end() && (it->second.primary != 0 || it->second.secondary != 0);
}

Keybind KeybindManager::get(const std::string& action) const {
    const auto it = binds_.find(action);
    if (it == binds_.end()) {
        return Keybind{};
    }
    return it->second;
}

std::vector<Keybind> KeybindManager::list() const {
    std::vector<Keybind> out;
    for (const auto& [id, b] : binds_) {
        out.push_back(b);
    }
    return out;
}

void KeybindManager::reset() {
    for (auto& [id, b] : binds_) {
        b.primary = 0;
        b.secondary = 0;
    }
    lastError_.clear();
}

} // namespace xykell::input
