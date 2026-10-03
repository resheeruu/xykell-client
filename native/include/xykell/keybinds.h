#pragma once

// Keybind system (Batch 4). Pure C++, no platform, no input capture.
// Actions are named capabilities (module toggles, quick actions); BINDINGS
// map them to key codes and/or touch regions. The manager never reads
// hardware or Android input — the host feeds discrete press events and
// queries what fired. Conflicts are reported, never silently overwritten.
// Never captures passwords or sensitive input: bindings reference abstract
// codes supplied by the host, and the manager stores no keystroke content.
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace xykell::input {

// Abstract key codes. Negative values are reserved for touch regions;
// non-negative values are host key codes (meaning defined by the host).
inline constexpr int kTouchBase = -1000;

struct Keybind {
    std::string action;  // e.g. "module.toggle.fps", "hud.editor.open"
    int primary = 0;     // 0 = unbound
    int secondary = 0;   // 0 = unbound
};

class KeybindManager {
  public:
    // Registers an action (idempotent description, keeps existing binds).
    // Returns false when the action id is empty.
    bool registerAction(const std::string& action);

    // Binds a code to an action. Returns false (and changes nothing) when
    // the action is unknown, the code is already bound elsewhere (conflict
    // reported via lastError), or the code is 0. Unbind first to rebind.
    bool bind(const std::string& action, int code, bool secondary = false);
    bool unbind(const std::string& action, bool secondary = false);

    // Host feeds a discrete press; returns the actions that fired (usually
    // 0 or 1; empty when unbound). Never records the press itself.
    std::vector<std::string> press(int code) const;

    // All codes currently bound (for conflict display in editors).
    std::vector<int> boundCodes() const;

    bool isBound(const std::string& action) const;
    Keybind get(const std::string& action) const;  // empty bind when unknown
    std::vector<Keybind> list() const;
    void reset();  // clears all binds, keeps registered actions

    const std::string& lastError() const { return lastError_; }

  private:
    std::unordered_map<std::string, Keybind> binds_;
    std::string lastError_;
};

// First-party default bindings (Batch 4). Touch-region codes only where
// the host documents them; keyboard codes are host-defined examples kept
// at 0 (unbound) unless the host assigns them — never assume a keyboard.
inline int registerDefaultBinds(KeybindManager& m) {
    static const char* kActions[] = {
        "hud.editor.open",
        "hud.editor.commit",
        "module.toggle.fps",
        "module.toggle.cps",
        "module.toggle.clock",
        "profile.next",
        "observation.start",
        "observation.stop",
    };
    int added = 0;
    for (const char* a : kActions) {
        if (m.registerAction(a)) {
            ++added;
        }
    }
    return added;
}

} // namespace xykell::input
