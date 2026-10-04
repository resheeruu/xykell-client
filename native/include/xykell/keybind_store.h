#pragma once

// Keybind persistence (Batch 13). File-backed binds for KeybindManager at
// <path>: {"version":1,"binds":{<action>:{"p":code,"s":code}, ...}} — only
// abstract host codes, never keystroke content. Load is recover-always:
// a missing file means "no stored binds" (true, empty error); a corrupt or
// wrong-version file is backed up beside the original and treated as empty
// (true, error explains what happened) so the editor can repair by
// rebinding. Entries with unknown actions or conflicting/out-of-range codes
// are skipped with an error note, never applied over valid state. Save is
// atomic (tmp + rename) and only writes actions that have a non-zero bind.
#include <string>

#include "xykell/keybinds.h"

namespace xykell::input {

inline constexpr int kBindStoreVersion = 1;
// Bound codes must be integral and inside this range (touch codes use
// kTouchBase = -1000 and below; host key codes are non-negative).
inline constexpr double kMinBindCode = -1000000.0;
inline constexpr double kMaxBindCode = 1000000.0;

bool loadBinds(KeybindManager& m, const std::string& path, std::string& error);
bool saveBinds(const KeybindManager& m, const std::string& path, std::string& error);

} // namespace xykell::input
