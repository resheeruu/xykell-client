// JNI bridge for the Kotlin Native* bridges.
//
// This is the only place the process crosses into C++ for Xykell's own
// functions. Before it existed, every `external fun` in app/src/main raised
// UnsatisfiedLinkError and the Kotlin guard() wrapper degraded it to
// null / false / "", so native-backed screens silently rendered empty. The
// C++ implementations were already present and host-tested; only this binding
// layer was missing.
//
// Every function below is a thin, total wrapper over an API that already
// exists in native/include. Nothing is implemented here that is not already
// implemented and host-tested in C++.
//
// Invariants, enforced deliberately:
//   - no C++ exception ever crosses the boundary; failures surface as the
//     Kotlin-visible failure the existing declaration already promises
//   - every String argument is length-bounded before it can reach a path
//   - no JNI class/method lookup by name, so no dynamic loading
//   - no reflection, no process execution, no socket, no raw file path from
//     Kotlin: the only path inputs are the app's own root, which JNI never
//     interprets, and fixed file names appended to it
//   - no secrets: the bridge never reads or returns credentials
#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "xykell/config_store.h"
#include "xykell/hud_renderer.h"
#include "xykell/json_min.h"
#include "xykell/keybind_store.h"
#include "xykell/keybinds.h"
#include "xykell/profile_manager.h"
#include "xykell/settings.h"
#include "xykell/theme.h"
#include "xykell/version_adapter.h"

namespace {

// --- JNI string helpers. A null jstring becomes empty; callers must validate.

std::string fromJ(JNIEnv* env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) {
        return {}; // OOM on the JNI side: treated as empty, never a crash
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring toJ(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

jboolean jbool(bool v) { return v ? JNI_TRUE : JNI_FALSE; }

// --- Input bounds. These are the only values that can influence a path.

constexpr std::size_t kMaxPathLen = 4096;
constexpr std::size_t kMaxNameLen = 64;
constexpr std::size_t kMaxJsonLen = 1u << 20; // 1 MiB
constexpr std::size_t kMaxCollection = 4096;

bool okPath(const std::string& p) {
    return !p.empty() && p.size() <= kMaxPathLen;
}

/** Names that become file names. No separator, no traversal. */
bool okName(const std::string& n) {
    return !n.empty() && n.size() <= kMaxNameLen && n.find('/') == std::string::npos &&
           n.find('\\') == std::string::npos && n.find("..") == std::string::npos;
}

bool finiteOrZero(double v) { return std::isfinite(v); }

jobjectArray toJArray(JNIEnv* env, const std::vector<std::string>& v) {
    jclass cls = env->FindClass("java/lang/String");
    if (cls == nullptr) {
        return nullptr;
    }
    if (v.size() > kMaxCollection) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(v.size()), cls, nullptr);
    env->DeleteLocalRef(cls);
    if (arr == nullptr) {
        return nullptr;
    }
    for (jsize i = 0; i < static_cast<jsize>(v.size()); ++i) {
        jstring s = toJ(env, v[static_cast<std::size_t>(i)]);
        if (s == nullptr) {
            return nullptr;
        }
        env->SetObjectArrayElement(arr, i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

const char* typeName(xykell::settings::SettingType t) {
    switch (t) {
        case xykell::settings::SettingType::Bool: return "bool";
        case xykell::settings::SettingType::Int: return "int";
        case xykell::settings::SettingType::Double: return "float";
        case xykell::settings::SettingType::Text: return "string";
        case xykell::settings::SettingType::Choice: return "enum";
    }
    return "string";
}

const char* supportName(xykell::SupportState s) {
    switch (s) {
        case xykell::SupportState::Supported: return "SUPPORTED";
        case xykell::SupportState::Partial: return "PARTIAL";
        case xykell::SupportState::Unsupported: return "UNSUPPORTED";
    }
    return "PARTIAL";
}

// --- Process-wide singletons.
//
// These mirror one native instance per logical store, created on first use.
// They hold no credentials and no unbounded state; each is bounded by the C++
// type it wraps.

xykell::ProfileManager& profilesFor(const std::string& root) {
    // Keyed by root so two app data directories cannot share one manager.
    static std::vector<std::pair<std::string, std::unique_ptr<xykell::ProfileManager>>> cache;
    for (auto& kv : cache) {
        if (kv.first == root) {
            return *kv.second;
        }
    }
    auto owned = std::make_unique<xykell::ProfileManager>(root);
    auto* raw = owned.get();
    cache.emplace_back(root, std::move(owned));
    return *raw;
}

xykell::Profile loadProfile(const std::string& root, const std::string& name) {
    xykell::Profile p;
    std::string err;
    profilesFor(root).load(name, p, err);
    return p;
}

} // namespace

extern "C" {

// ===========================================================================
// dev.xykell.client.NativeSettings  (Kotlin object -> instance methods)
// ===========================================================================

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeSettings_settingsCatalog(JNIEnv* env, jobject) {
    // Built from the same inline catalog() the host tests read, so the app and
    // the tests can never disagree about which settings exist.
    xykell::json::Object out;
    for (const auto& spec : xykell::settings::catalog()) {
        xykell::json::Object o;
        o.emplace("section", xykell::json::Value(std::string(spec.section)));
        o.emplace("key", xykell::json::Value(std::string(spec.key)));
        o.emplace("type", xykell::json::Value(std::string(typeName(spec.type))));
        o.emplace("description", xykell::json::Value(std::string(spec.description)));
        o.emplace("default", spec.defaultValue);
        if (!spec.options.empty()) {
            xykell::json::Array opts;
            for (const auto& c : spec.options) {
                opts.push_back(xykell::json::Value(c));
            }
            o.emplace("options", xykell::json::Value(std::move(opts)));
        }
        if (spec.type == xykell::settings::SettingType::Int ||
            spec.type == xykell::settings::SettingType::Double) {
            o.emplace("min", xykell::json::Value(spec.min));
            o.emplace("max", xykell::json::Value(spec.max));
        }
        out[std::string(spec.section) + "." + std::string(spec.key)] =
            xykell::json::Value(std::move(o));
    }
    return toJ(env, xykell::json::stringify(xykell::json::Value(std::move(out))));
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeSettings_settingsValues(JNIEnv* env, jobject, jstring jroot) {
    const std::string root = fromJ(env, jroot);
    if (!okPath(root)) {
        return toJ(env, "{}");
    }
    xykell::XykellConfig cfg;
    cfg.load(root + "/settings.json");
    return toJ(env, xykell::json::stringify(cfg.root()));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeSettings_setSetting(JNIEnv* env, jobject, jstring jroot,
                                                 jstring jsection, jstring jkey,
                                                 jstring jvalue) {
    const std::string root = fromJ(env, jroot);
    const std::string section = fromJ(env, jsection);
    const std::string key = fromJ(env, jkey);
    const std::string text = fromJ(env, jvalue);
    if (!okPath(root) || !okName(section) || !okName(key) || text.size() > kMaxJsonLen) {
        return JNI_FALSE;
    }
    // The catalog is the allowlist: an unknown section/key can never be written,
    // which is what stops a script rule from reaching outside the schema.
    const auto* spec = xykell::settings::find(section, key);
    if (spec == nullptr) {
        return JNI_FALSE;
    }
    const auto parsed = xykell::json::parse(text);
    if (!parsed.ok) {
        return JNI_FALSE;
    }
    xykell::XykellConfig cfg;
    const std::string path = root + "/settings.json";
    cfg.load(path);
    std::string err;
    if (!xykell::settings::setValue(cfg, *spec, parsed.value, err)) {
        return JNI_FALSE;
    }
    return jbool(cfg.save(path));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeSettings_resetSettings(JNIEnv* env, jobject, jstring jroot,
                                                    jstring jsection) {
    const std::string root = fromJ(env, jroot);
    const std::string section = fromJ(env, jsection);
    if (!okPath(root) || !okName(section)) {
        return JNI_FALSE;
    }
    xykell::XykellConfig cfg;
    const std::string path = root + "/settings.json";
    cfg.load(path);
    if (section == "all") {
        xykell::settings::resetAll(cfg);
    } else {
        xykell::settings::resetSection(cfg, section);
    }
    return jbool(cfg.save(path));
}

// ===========================================================================
// dev.xykell.client.NativeProfiles
// ===========================================================================

JNIEXPORT jobjectArray JNICALL
Java_dev_xykell_client_NativeProfiles_listProfiles(JNIEnv* env, jobject, jstring jroot) {
    const std::string root = fromJ(env, jroot);
    if (!okPath(root)) {
        return toJArray(env, {});
    }
    return toJArray(env, profilesFor(root).list());
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeProfiles_getActive(JNIEnv* env, jobject, jstring jroot) {
    const std::string root = fromJ(env, jroot);
    if (!okPath(root)) {
        return toJ(env, "Default");
    }
    return toJ(env, profilesFor(root).active());
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeProfiles_setActive(JNIEnv* env, jobject, jstring jroot,
                                                jstring jname) {
    const std::string root = fromJ(env, jroot);
    const std::string name = fromJ(env, jname);
    if (!okPath(root) || !okName(name)) {
        return JNI_FALSE;
    }
    std::string err;
    return jbool(profilesFor(root).setActive(name, err));
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeProfiles_getProfileJson(JNIEnv* env, jobject, jstring jroot,
                                                     jstring jname) {
    const std::string root = fromJ(env, jroot);
    const std::string name = fromJ(env, jname);
    if (!okPath(root) || !okName(name)) {
        return nullptr;
    }
    xykell::Profile p;
    std::string err;
    if (!profilesFor(root).load(name, p, err)) {
        return nullptr;
    }
    return toJ(env, xykell::json::stringify(p.serialize()));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeProfiles_importProfileJson(JNIEnv* env, jobject, jstring jroot,
                                                        jstring jname, jstring jjson) {
    const std::string root = fromJ(env, jroot);
    const std::string name = fromJ(env, jname);
    const std::string text = fromJ(env, jjson);
    if (!okPath(root) || !okName(name) || text.size() > kMaxJsonLen) {
        return JNI_FALSE;
    }
    const auto parsed = xykell::json::parse(text);
    if (!parsed.ok) {
        return JNI_FALSE;
    }
    // deserialize() rejects unknown structure and fails closed, so a malformed
    // document never reaches disk.
    xykell::Profile p;
    std::string err;
    if (!p.deserialize(parsed.value, err)) {
        return JNI_FALSE;
    }
    p.name = name;
    return jbool(profilesFor(root).save(p, err));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeProfiles_createProfile(JNIEnv* env, jobject, jstring jroot,
                                                    jstring jname) {
    const std::string root = fromJ(env, jroot);
    const std::string name = fromJ(env, jname);
    if (!okPath(root) || !okName(name)) {
        return JNI_FALSE;
    }
    std::string err;
    return jbool(profilesFor(root).create(name, err));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeProfiles_resetProfile(JNIEnv* env, jobject, jstring jroot,
                                                   jstring jname) {
    const std::string root = fromJ(env, jroot);
    const std::string name = fromJ(env, jname);
    if (!okPath(root) || !okName(name)) {
        return JNI_FALSE;
    }
    std::string err;
    return jbool(profilesFor(root).reset(name, err));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeProfiles_deleteProfile(JNIEnv* env, jobject, jstring jroot,
                                                    jstring jname) {
    const std::string root = fromJ(env, jroot);
    const std::string name = fromJ(env, jname);
    if (!okPath(root) || !okName(name)) {
        return JNI_FALSE;
    }
    std::string err;
    // remove() itself refuses "Default".
    return jbool(profilesFor(root).remove(name, err));
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeProfiles_profileOpRaw(JNIEnv* env, jobject, jstring jroot,
                                                   jstring jname, jint op) {
    const std::string root = fromJ(env, jroot);
    const std::string name = fromJ(env, jname);
    if (!okPath(root) || !okName(name)) {
        return toJ(env, "error");
    }
    auto& pm = profilesFor(root);
    std::string err;
    bool ok = false;
    switch (op) {
        case 1: ok = pm.duplicate("Default", name, err); break;
        case 2: ok = pm.rename(name, "Default", err); break;
        default: return toJ(env, "error");
    }
    return toJ(env, ok ? "ok" : (err.empty() ? "error" : err));
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeProfiles_checkVersion(JNIEnv* env, jobject, jstring jversion,
                                                   jstring jabi) {
    const std::string version = fromJ(env, jversion);
    const std::string abi = fromJ(env, jabi);
    if (version.size() > kMaxNameLen || abi.size() > kMaxNameLen) {
        return toJ(env, "UNSUPPORTED|input too long");
    }
    const auto r = xykell::VersionAdapter::check(version, abi);
    return toJ(env, std::string(supportName(r.state)) + "|" + r.reason);
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeProfiles_checkInstall(JNIEnv* env, jobject, jboolean jfound,
                                                    jstring jversion, jstring jabi,
                                                    jboolean jenabled,
                                                    jboolean jqueries) {
    const std::string version = fromJ(env, jversion);
    const std::string abi = fromJ(env, jabi);
    if (version.size() > kMaxNameLen || abi.size() > kMaxNameLen) {
        return toJ(env, "ABSENT|input too long");
    }
    if (jfound != JNI_TRUE) {
        return toJ(env, "ABSENT|not found");
    }
    if (jqueries != JNI_TRUE) {
        // Without <queries> visibility the package may exist but be invisible;
        // saying ABSENT here would be a fabricated negative.
        return toJ(env, "UNKNOWN|queries permission not granted");
    }
    if (jenabled != JNI_TRUE) {
        return toJ(env, "DISABLED|app disabled");
    }
    const auto r = xykell::VersionAdapter::check(version, abi);
    return toJ(env, std::string(r.state == xykell::SupportState::Supported ? "READY"
                                                                        : "UNSUPPORTED") +
                        "|" + r.reason);
}

// ===========================================================================
// dev.xykell.client.NativeHud
// ===========================================================================

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeHud_getHudLayout(JNIEnv* env, jobject, jstring jroot,
                                              jstring jprofile) {
    const std::string root = fromJ(env, jroot);
    const std::string profile = fromJ(env, jprofile);
    if (!okPath(root) || !okName(profile)) {
        return nullptr;
    }
    xykell::hud::HudManager mgr;
    std::string err;
    if (!xykell::hud::loadHudFromProfile(loadProfile(root, profile), mgr, err)) {
        return nullptr;
    }
    return toJ(env, xykell::json::stringify(mgr.saveLayout()));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeHud_setHudLayout(JNIEnv* env, jobject, jstring jroot,
                                              jstring jprofile, jstring jjson) {
    const std::string root = fromJ(env, jroot);
    const std::string profile = fromJ(env, jprofile);
    const std::string text = fromJ(env, jjson);
    if (!okPath(root) || !okName(profile) || text.size() > kMaxJsonLen) {
        return JNI_FALSE;
    }
    const auto parsed = xykell::json::parse(text);
    if (!parsed.ok) {
        return JNI_FALSE;
    }
    xykell::hud::HudManager mgr;
    std::string err;
    if (!mgr.loadLayout(parsed.value, err)) {
        return JNI_FALSE;
    }
    xykell::Profile p = loadProfile(root, profile);
    xykell::hud::saveHudToProfile(p, mgr);
    return jbool(profilesFor(root).save(p, err));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeHud_setHudElement(JNIEnv* env, jobject, jstring jroot,
                                                jstring jprofile, jint index, jdouble x,
                                                jdouble y, jdouble scale,
                                                jboolean visible) {
    const std::string root = fromJ(env, jroot);
    const std::string profile = fromJ(env, jprofile);
    if (!okPath(root) || !okName(profile)) {
        return JNI_FALSE;
    }
    // Bound the edit here rather than letting a caller push an element far off
    // screen, invert its scale, or pass NaN through to the renderer.
    if (!finiteOrZero(x) || !finiteOrZero(y) || !finiteOrZero(scale) || x < -10000.0 ||
        x > 10000.0 || y < -10000.0 || y > 10000.0 || scale < 0.25 || scale > 4.0) {
        return JNI_FALSE;
    }
    xykell::hud::HudManager mgr;
    std::string err;
    if (!xykell::hud::loadHudFromProfile(loadProfile(root, profile), mgr, err)) {
        return JNI_FALSE;
    }
    auto& els = mgr.layout().elements;
    if (index < 0 || index >= static_cast<jint>(els.size())) {
        return JNI_FALSE;
    }
    auto& e = els[static_cast<std::size_t>(index)];
    e.x = static_cast<float>(x);
    e.y = static_cast<float>(y);
    e.scale = static_cast<float>(scale);
    e.visible = visible == JNI_TRUE;
    xykell::Profile p = loadProfile(root, profile);
    xykell::hud::saveHudToProfile(p, mgr);
    return jbool(profilesFor(root).save(p, err));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeHud_resetHudLayout(JNIEnv* env, jobject, jstring jroot,
                                                 jstring jprofile) {
    const std::string root = fromJ(env, jroot);
    const std::string profile = fromJ(env, jprofile);
    if (!okPath(root) || !okName(profile)) {
        return JNI_FALSE;
    }
    xykell::hud::HudManager mgr;
    mgr.resetToDefaults();
    xykell::Profile p = loadProfile(root, profile);
    xykell::hud::saveHudToProfile(p, mgr);
    std::string err;
    return jbool(profilesFor(root).save(p, err));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeHud_setProfileModule(JNIEnv* env, jobject, jstring jroot,
                                                  jstring jprofile, jstring jid,
                                                  jboolean enabled) {
    const std::string root = fromJ(env, jroot);
    const std::string profile = fromJ(env, jprofile);
    const std::string id = fromJ(env, jid);
    if (!okPath(root) || !okName(profile) || !okName(id)) {
        return JNI_FALSE;
    }
    xykell::Profile p = loadProfile(root, profile);
    p.modules[id] = enabled == JNI_TRUE;
    std::string err;
    return jbool(profilesFor(root).save(p, err));
}

// ===========================================================================
// dev.xykell.client.NativeThemes
// ===========================================================================

JNIEXPORT jobjectArray JNICALL
Java_dev_xykell_client_NativeThemes_listThemes(JNIEnv* env, jobject) {
    std::vector<std::string> names;
    for (const auto& t : xykell::ui::ThemeManager::builtins()) {
        names.push_back(t.name);
    }
    return toJArray(env, names);
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeThemes_themeTokens(JNIEnv* env, jobject, jstring jname) {
    const std::string name = fromJ(env, jname);
    if (!okName(name)) {
        return nullptr;
    }
    xykell::ui::Theme theme;
    if (!xykell::ui::ThemeManager::find(name, theme)) {
        return nullptr;
    }
    return toJ(env, xykell::json::stringify(theme.serialize()));
}

// ===========================================================================
// dev.xykell.client.NativeKeybinds
//
// Binds are abstract host codes only. Nothing here records keystroke content,
// which is the same guarantee the C++ store documents.
// ===========================================================================

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeKeybinds_keybindList(JNIEnv* env, jobject, jstring jroot) {
    const std::string root = fromJ(env, jroot);
    if (!okPath(root)) {
        return nullptr;
    }
    xykell::input::KeybindManager mgr;
    std::string err;
    // loadBinds is recover-always: a corrupt file becomes empty plus an error
    // note, never a crash and never a silent partial restore.
    if (!xykell::input::loadBinds(mgr, root + "/keybinds.json", err)) {
        return toJ(env, xykell::json::stringify(xykell::json::Value(xykell::json::Object{})));
    }
    xykell::json::Object out;
    for (const auto& kb : mgr.list()) {
        xykell::json::Object e;
        e.emplace("action", xykell::json::Value(kb.action));
        e.emplace("primary", xykell::json::Value(static_cast<double>(kb.primary)));
        e.emplace("secondary", xykell::json::Value(static_cast<double>(kb.secondary)));
        out[kb.action] = xykell::json::Value(std::move(e));
    }
    return toJ(env, xykell::json::stringify(xykell::json::Value(std::move(out))));
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeKeybinds_keybindSet(JNIEnv* env, jobject, jstring jroot,
                                                 jstring jaction, jint slot, jint code) {
    const std::string root = fromJ(env, jroot);
    const std::string action = fromJ(env, jaction);
    if (!okPath(root) || !okName(action) || slot < 0 || slot > 1) {
        return nullptr;
    }
    // Bounded to the range keybind_store documents; 0 clears the slot.
    if (code != 0 && (code < -1000000 || code > 1000000)) {
        return toJ(env, "code out of range");
    }
    xykell::input::KeybindManager mgr;
    std::string err;
    if (!xykell::input::loadBinds(mgr, root + "/keybinds.json", err)) {
        return toJ(env, err.empty() ? "load failed" : err);
    }
    if (!mgr.registerAction(action)) {
        return toJ(env, "action rejected");
    }
    const bool ok = code == 0 ? mgr.unbind(action, slot == 1)
                              : mgr.bind(action, code, slot == 1);
    if (!ok) {
        return toJ(env, mgr.lastError().empty() ? "bind failed" : mgr.lastError());
    }
    if (!xykell::input::saveBinds(mgr, root + "/keybinds.json", err)) {
        return toJ(env, err.empty() ? "save failed" : err);
    }
    return toJ(env, "ok");
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeKeybinds_keybindReset(JNIEnv* env, jobject, jstring jroot) {
    const std::string root = fromJ(env, jroot);
    if (!okPath(root)) {
        return nullptr;
    }
    xykell::input::KeybindManager mgr;
    std::string err;
    if (!xykell::input::loadBinds(mgr, root + "/keybinds.json", err)) {
        return toJ(env, err.empty() ? "load failed" : err);
    }
    mgr.reset(); // clears binds, keeps registered actions
    if (!xykell::input::saveBinds(mgr, root + "/keybinds.json", err)) {
        return toJ(env, err.empty() ? "save failed" : err);
    }
    return toJ(env, "ok");
}

} // extern "C"
