#include "xykell/keybind_store.h"

#include <chrono>
#include <cmath>

#include "xykell/file_util.h"
#include "xykell/json_min.h"

namespace xykell::input {

namespace {

bool validCode(int code) {
    if (code == 0) return true;
    const double d = static_cast<double>(code);
    return d >= kMinBindCode && d <= kMaxBindCode;
}

// Reads an integral code field; returns -999999999 sentinel when the field
// is missing, non-numeric or non-integral so callers reject rather than
// guess.
int codeField(const json::Object& o, const char* key) {
    const auto it = o.find(key);
    if (it == o.end() || !it->second.isNumber()) return -999999999;
    const double d = it->second.asNumber();
    if (std::floor(d) != d) return -999999999;
    if (d < kMinBindCode || d > kMaxBindCode) return -999999999;
    return static_cast<int>(d);
}

void backupCorrupt(const std::string& path, const std::string& content,
                   std::string& error) {
    const auto stamp = std::chrono::system_clock::now().time_since_epoch().count();
    const std::string backup = path + ".corrupt." + std::to_string(stamp);
    std::string ignored;
    const auto slash = path.find_last_of("/\\");
    if (slash != std::string::npos) {
        fs::ensureDir(path.substr(0, slash), ignored);
    }
    std::string werr;
    if (fs::atomicWrite(backup, content, werr)) {
        error += " (corrupt copy kept at " + backup + ")";
    } else {
        error += " (backup also failed: " + werr + ")";
    }
}

} // namespace

bool loadBinds(KeybindManager& m, const std::string& path, std::string& error) {
    error.clear();
    const auto read = fs::readFile(path);
    if (!read.ok) {
        return true;  // missing file: no stored binds, not an error
    }
    const auto parsed = json::parse(read.content);
    if (!parsed.ok) {
        error = "keybinds file corrupt: " + parsed.error;
        backupCorrupt(path, read.content, error);
        return true;
    }
    const auto& root = parsed.value.asObject(json::Value::emptyObject());
    const auto vit = root.find("version");
    const int version = (vit != root.end() && vit->second.isNumber())
        ? static_cast<int>(vit->second.asNumber(-1))
        : -1;
    if (version != kBindStoreVersion) {
        error = "unsupported keybinds version " + std::to_string(version);
        backupCorrupt(path, read.content, error);
        return true;
    }
    std::string notes;
    const auto bit = root.find("binds");
    if (bit != root.end() && bit->second.isObject()) {
        const auto& binds = bit->second.asObject(json::Value::emptyObject());
        for (const auto& [action, val] : binds) {
            if (!val.isObject()) {
                notes += action + ": not an object; ";
                continue;
            }
            const auto& b = val.asObject(json::Value::emptyObject());
            const int p = codeField(b, "p");
            const int sRaw = codeField(b, "s");
            if (p == -999999999 || sRaw == -999999999) {
                notes += action + ": invalid code; ";
                continue;
            }
            if (p == 0 && sRaw == 0) continue;
            // Only actions registered by the caller (defaults or runtime
            // code) may receive stored binds — the file never creates new
            // action names.
            if (m.get(action).action.empty()) {
                notes += action + ": unknown action; ";
                continue;
            }
            if (!validCode(p) || !validCode(sRaw)) {
                notes += action + ": code out of range; ";
                continue;
            }
            const int s = (sRaw == p) ? 0 : sRaw;  // degenerate dup dropped
            if (p != 0 && !m.bind(action, p)) {
                notes += action + ": " + m.lastError() + "; ";
                continue;
            }
            if (s != 0 && !m.bind(action, s, true)) {
                notes += action + ": secondary " + m.lastError() + "; ";
            }
        }
    } else {
        notes += "binds missing or not an object; ";
    }
    if (!notes.empty()) {
        error = "skipped entries: " + notes;
    }
    return true;
}

bool saveBinds(const KeybindManager& m, const std::string& path,
               std::string& error) {
    error.clear();
    json::Object binds;
    for (const auto& kb : m.list()) {
        if (kb.primary == 0 && kb.secondary == 0) continue;
        json::Object b;
        b.emplace("p", json::Value(kb.primary));
        b.emplace("s", json::Value(kb.secondary));
        binds.emplace(kb.action, json::Value(std::move(b)));
    }
    json::Object root;
    root.emplace("version", json::Value(kBindStoreVersion));
    root.emplace("binds", json::Value(std::move(binds)));
    const auto slash = path.find_last_of("/\\");
    if (slash != std::string::npos) {
        std::string derr;
        if (!fs::ensureDir(path.substr(0, slash), derr)) {
            error = "cannot create dir: " + derr;
            return false;
        }
    }
    return fs::atomicWrite(path, json::stringify(json::Value(std::move(root))),
                           error);
}

} // namespace xykell::input
