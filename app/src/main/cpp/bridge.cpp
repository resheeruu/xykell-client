// JNI bridge: launcher UI -> the SAME ProfileManager implementation the
// native game module uses (shared code, no duplication). Storage roots differ
// per sandbox (documented in LAUNCHER-INTEGRATION.md): this bridge serves the
// launcher's own store; the game-process store syncs via export/import files.
#include <jni.h>

#include <limits>
#include <string>
#include <vector>

#include "xykell/profile_manager.h"
#include "xykell/detection.h"
#include "xykell/version_adapter.h"
#include "xykell/runtime_provider.h"
#include "xykell/runtime_observation_consumer.h"
#include "xykell/config_store.h"
#include "xykell/hud_model.h"
#include "xykell/settings.h"
#include "xykell/theme.h"

namespace {

std::string toStd(JNIEnv* env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = (chars != nullptr) ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(s, chars);
    }
    return out;
}

jstring toJni(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

// Process-wide substrate instance for the status bridge. Kotlin never sees
// a native pointer (strings/bools only cross the boundary).
xykell::runtime::Runtime& sharedRuntime() {
    static xykell::runtime::Runtime rt;
    return rt;
}

std::string jsonEscape(const std::string& s) {
    std::string out;
    for (char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            default: out += c; break;
        }
    }
    return out;
}

// Process-wide observation consumer for Stage-20 field-only offers.
// Kotlin never sees a native pointer: primitives/strings in, bool out.
// No JSON, envelopes, bytes, keys, ciphertext, or commands cross here.
xykell::runtime::ObservationConsumer& sharedObservationConsumer() {
    static xykell::runtime::ObservationConsumer consumer;
    return consumer;
}

} // namespace

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_dev_xykell_client_NativeProfiles_listProfiles(JNIEnv* env, jclass, jstring root) {
    xykell::ProfileManager pm(toStd(env, root));
    const auto names = pm.list();
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(names.size()), strCls, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(names.size()); ++i) {
        env->SetObjectArrayElement(arr, i, toJni(env, names[i]));
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeProfiles_getActive(JNIEnv* env, jclass, jstring root) {
    xykell::ProfileManager pm(toStd(env, root));
    return toJni(env, pm.active());
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeProfiles_setActive(JNIEnv* env, jclass, jstring root,
                                                jstring name) {
    xykell::ProfileManager pm(toStd(env, root));
    std::string err;
    return static_cast<jboolean>(pm.setActive(toStd(env, name), err));
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeProfiles_getProfileJson(JNIEnv* env, jclass, jstring root,
                                                     jstring name) {
    xykell::ProfileManager pm(toStd(env, root));
    xykell::Profile p;
    std::string err;
    if (!pm.load(toStd(env, name), p, err)) {
        return nullptr;
    }
    return toJni(env, xykell::json::stringify(p.serialize()));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeProfiles_importProfileJson(JNIEnv* env, jclass, jstring root,
                                                        jstring name, jstring jsonText) {    const std::string jsonStr = toStd(env, jsonText);
    const auto parsed = xykell::json::parse(jsonStr);
    if (!parsed.ok) {
        return JNI_FALSE;
    }
    xykell::Profile p;
    std::string err;
    if (!p.deserialize(parsed.value, err)) {
        return JNI_FALSE; // corrupt import rejected by native validation
    }
    p.name = toStd(env, name);
    xykell::ProfileManager pm(toStd(env, root));
    return static_cast<jboolean>(pm.save(p, err));
}

// Profile lifecycle: typed string/bool only, mirroring the host-tested
// ProfileManager. Invalid names, duplicates, and Default deletion are
// rejected natively with false (never an exception across JNI).
JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeProfiles_createProfile(JNIEnv* env, jclass, jstring root,
                                                    jstring name) {
    xykell::ProfileManager pm(toStd(env, root));
    std::string err;
    return static_cast<jboolean>(pm.create(toStd(env, name), err));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeProfiles_resetProfile(JNIEnv* env, jclass, jstring root,
                                                   jstring name) {
    xykell::ProfileManager pm(toStd(env, root));
    std::string err;
    return static_cast<jboolean>(pm.reset(toStd(env, name), err));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeProfiles_deleteProfile(JNIEnv* env, jclass, jstring root,
                                                    jstring name) {
    xykell::ProfileManager pm(toStd(env, root));
    std::string err;
    return static_cast<jboolean>(pm.remove(toStd(env, name), err));
}

// Batch 11: same four lifecycle ops with the native error surfaced to the
// UI. Returns "" on success, otherwise the exact native reason (never
// invented). Bounded op switch — no free-form command crosses JNI.
JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeProfiles_profileOpRaw(JNIEnv* env, jclass, jstring root,
                                                    jstring name, jint op) {
    xykell::ProfileManager pm(toStd(env, root));
    const std::string target = toStd(env, name);
    std::string err;
    bool ok = false;
    switch (op) {
        case 0: ok = pm.setActive(target, err); break;
        case 1: ok = pm.create(target, err); break;
        case 2: ok = pm.reset(target, err); break;
        case 3: ok = pm.remove(target, err); break;
        default: err = "unknown profile op"; break;
    }
    if (ok) {
        return env->NewStringUTF("");
    }
    return toJni(env, err.empty() ? std::string("operation rejected") : err);
}

// Settings domain bridge (Batch 7). Confined to the settings catalog:
// catalog snapshot, current values, validated set, scoped reset. NOT a
// generic JSON bridge — section/key/value only, validated natively.
namespace {

std::string settingsPath(const std::string& root) {
    return root + "/xykell.json";
}

bool loadConfig(const std::string& root, xykell::XykellConfig& cfg) {
    return cfg.load(settingsPath(root));
}

bool saveConfig(const std::string& root, xykell::XykellConfig& cfg) {
    return cfg.save(settingsPath(root));
}

} // namespace

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeSettings_settingsCatalog(JNIEnv* env, jclass) {
    try {
        xykell::json::Object out;
        for (const auto& s : xykell::settings::catalog()) {
            xykell::json::Object spec;
            spec.emplace("section", xykell::json::Value(std::string(s.section)));
            spec.emplace("key", xykell::json::Value(std::string(s.key)));
            const char* type = "text";
            switch (s.type) {
                case xykell::settings::SettingType::Bool: type = "bool"; break;
                case xykell::settings::SettingType::Int: type = "int"; break;
                case xykell::settings::SettingType::Double: type = "double"; break;
                case xykell::settings::SettingType::Text: type = "text"; break;
                case xykell::settings::SettingType::Choice: type = "choice"; break;
            }
            spec.emplace("type", xykell::json::Value(std::string(type)));
            spec.emplace("default", s.defaultValue);
            spec.emplace("min", xykell::json::Value(s.min));
            spec.emplace("max", xykell::json::Value(s.max));
            xykell::json::Array opts;
            for (const auto& o : s.options) {
                opts.push_back(xykell::json::Value(o));
            }
            spec.emplace("options", xykell::json::Value(std::move(opts)));
            spec.emplace("description", xykell::json::Value(std::string(s.description)));
            out.emplace(std::string(s.section) + "." + s.key,
                        xykell::json::Value(std::move(spec)));
        }
        return toJni(env, xykell::json::stringify(xykell::json::Value(std::move(out))));
    } catch (...) {
        return toJni(env, "{}");
    }
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeSettings_settingsValues(JNIEnv* env, jclass, jstring root) {
    try {
        xykell::XykellConfig cfg;
        loadConfig(toStd(env, root), cfg);
        return toJni(env, xykell::json::stringify(cfg.root()));
    } catch (...) {
        return toJni(env, "{}");
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeSettings_setSetting(JNIEnv* env, jclass, jstring root,
                                                 jstring section, jstring key,
                                                 jstring valueJson) {
    try {
        const std::string sec = toStd(env, section);
        const std::string k = toStd(env, key);
        const auto parsed = xykell::json::parse(toStd(env, valueJson));
        if (!parsed.ok) {
            return JNI_FALSE;
        }
        const xykell::settings::SettingSpec* spec = xykell::settings::find(sec, k);
        if (spec == nullptr) {
            return JNI_FALSE;  // undeclared setting: never stored
        }
        std::string error;
        if (!xykell::settings::validate(*spec, parsed.value, error)) {
            return JNI_FALSE;  // invalid value rejected with reason (logged below)
        }
        xykell::XykellConfig cfg;
        loadConfig(toStd(env, root), cfg);
        if (!xykell::settings::setValue(cfg, *spec, parsed.value, error)) {
            return JNI_FALSE;
        }
        return static_cast<jboolean>(saveConfig(toStd(env, root), cfg));
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeSettings_resetSettings(JNIEnv* env, jclass, jstring root,
                                                    jstring section) {    try {
        xykell::XykellConfig cfg;
        loadConfig(toStd(env, root), cfg);
        const std::string sec = toStd(env, section);
        if (sec.empty()) {
            xykell::settings::resetAll(cfg);
        } else {
            xykell::settings::resetSection(cfg, sec);
        }
        return static_cast<jboolean>(saveConfig(toStd(env, root), cfg));
    } catch (...) {
        return JNI_FALSE;
    }
}

// Shared version verdict: the SAME VersionAdapter the game module uses.
// Returns "STATE|reason", e.g. "SUPPORTED|matches Levi-verified line".
JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeProfiles_checkVersion(JNIEnv* env, jclass, jstring version,
                                                   jstring abi) {    const auto r = xykell::VersionAdapter::check(toStd(env, version), toStd(env, abi));
    std::string state;
    switch (r.state) {
        case xykell::SupportState::Supported: state = "SUPPORTED"; break;
        case xykell::SupportState::Partial: state = "PARTIAL"; break;
        case xykell::SupportState::Unsupported: state = "UNSUPPORTED"; break;
    }
    return toJni(env, state + "|" + r.reason);
}

// Installation verdict: the SAME detection rules as the game module.
// found/version/abi/enabled come from PackageManager; queriesGranted is true
// when the caller holds <queries> visibility (else every miss is ambiguous).
// Returns "STATE|reason".
JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeProfiles_checkInstall(JNIEnv* env, jclass, jboolean found,
                                                   jstring version, jstring abi,
                                                   jboolean enabled, jboolean queriesGranted) {
    xykell::detect::DetectionInput in;
    in.packageFound = (found == JNI_TRUE);
    in.version = toStd(env, version);
    in.abi = toStd(env, abi);
    in.enabled = (enabled == JNI_TRUE);
    in.queriesGranted = (queriesGranted == JNI_TRUE);
    const auto v = xykell::detect::evaluateDetection(in);
    return toJni(env, xykell::detect::stateName(v.state) + "|" + v.reason);
}

// Runtime status bridge (READ-ONLY): launcher UI observes the shared native
// substrate; no gameplay control crosses this boundary. Every body is
// exception-safe so native failure surfaces as status, never a crash.
JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeStart(JNIEnv*, jclass) {
    try {
        return static_cast<jboolean>(sharedRuntime().start());
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeStop(JNIEnv*, jclass) {
    try {
        sharedRuntime().stop();
    } catch (...) {
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeSelectProvider(JNIEnv* env, jclass,
                                                                 jstring name) {
    try {
        return static_cast<jboolean>(sharedRuntime().selectProvider(toStd(env, name)));
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeStatus(JNIEnv* env, jclass) {
    try {
        const auto& rt = sharedRuntime();
        const auto d = rt.diagnostics();
        return toJni(env, std::string("{\"state\":\"") +
                             xykell::runtime::toString(d.state) + "\",\"provider\":\"" +
                             jsonEscape(d.provider) + "\",\"lastError\":\"" +
                             jsonEscape(d.lastError) + "\"}");
    } catch (...) {
        return toJni(env, "{\"state\":\"FAILED\",\"provider\":\"\",\"lastError\":\"bridge\"}");
    }
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeCapabilities(JNIEnv* env, jclass) {
    try {
        std::string out = "[";
        bool first = true;
        for (const auto& c : sharedRuntime().capabilities()) {
            if (!first) out += ",";
            first = false;
            out += "\"" + jsonEscape(c.name) + "\"";
        }
        return toJni(env, out + "]");
    } catch (...) {
        return toJni(env, "[]");
    }
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeEndpoints(JNIEnv* env, jclass) {
    try {
        std::string out = "[";
        bool first = true;
        for (const auto& e : sharedRuntime().discover()) {
            if (!first) out += ",";
            first = false;
            out += "{\"id\":\"" + jsonEscape(e.id) + "\",\"displayName\":\"" +
                   jsonEscape(e.displayName) + "\"}";
        }
        return toJni(env, out + "]");
    } catch (...) {
        return toJni(env, "[]");
    }
}

// Discovery state (READ-ONLY): endpoint registry + selection + last
// diagnostic. No LAN control beyond what the provider already exposes.
JNIEXPORT jstring JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeDiscovery(JNIEnv* env, jclass) {
    try {
        const auto& rt = sharedRuntime();
        const auto d = rt.diagnostics();
        std::string out = std::string("{\"running\":") +
                          (d.state == xykell::runtime::ProviderState::Running ? "true"
                                                                             : "false") +
                          ",\"selected\":\"" + jsonEscape(rt.selectedEndpoint()) +
                          "\",\"diagnostic\":\"" + jsonEscape(d.lastError) + "\"}";
        return toJni(env, out);
    } catch (...) {
        return toJni(env, "{\"running\":false,\"selected\":\"\",\"diagnostic\":\"bridge\"}");
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeSelectEndpoint(JNIEnv* env, jclass,
                                                                 jstring id) {
    try {
        return static_cast<jboolean>(sharedRuntime().selectEndpoint(toStd(env, id)));
    } catch (...) {
        return JNI_FALSE;
    }
}

// Runtime session (READ-ONLY): Xykell-owned launch bookkeeping. `connected`
// is always false here: firing a system intent is not runtime attachment.
JNIEXPORT jstring JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeBeginSession(JNIEnv* env, jclass,
                                                               jstring pkg,
                                                               jstring version) {
    try {
        const auto s = sharedRuntime().beginSession(toStd(env, pkg), toStd(env, version));
        return toJni(env, std::string("{\"sessionId\":\"") + jsonEscape(s.sessionId) +
                             "\",\"connected\":false,\"diagnostics\":\"" +
                             jsonEscape(s.diagnostics) + "\"}");
    } catch (...) {
        return toJni(env, "{\"sessionId\":\"\",\"connected\":false,\"diagnostic\":\"bridge\"}");
    }
}

JNIEXPORT void JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeMarkLaunched(JNIEnv*, jclass) {
    try {
        sharedRuntime().markSessionLaunched();
    } catch (...) {
    }
}

JNIEXPORT void JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeEndSession(JNIEnv* env, jclass,
                                                             jstring reason) {
    try {
        sharedRuntime().endSession(toStd(env, reason));
    } catch (...) {
    }
}

// Observation offers (READ-ONLY, field-only): each builds a Stage-10 model
// object through its validating factory; invalid input is rejected (false)
// and never stored. No envelope/JSON/bytes/key/ciphertext crosses JNI.
JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_observation_Observations_nativeOfferPlayerMessage(
    JNIEnv* env, jclass, jstring eventId, jlong observedAtMs, jstring sender,
    jstring message) {
    try {
        auto o = xykell::runtime::makePlayerMessage(
            toStd(env, eventId), static_cast<std::uint64_t>(observedAtMs), toStd(env, sender),
            toStd(env, message));
        if (!o.has_value()) {
            return JNI_FALSE;
        }
        sharedObservationConsumer().consume(xykell::runtime::RuntimeObservation{*o});
        return JNI_TRUE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_observation_Observations_nativeOfferPlayerTravelled(
    JNIEnv* env, jclass, jstring eventId, jlong observedAtMs, jdouble x, jdouble y, jdouble z,
    jdouble yawDegrees, jdouble metersTravelled, jint travelMethod) {
    try {
        auto o = xykell::runtime::makePlayerTravel(
            toStd(env, eventId), static_cast<std::uint64_t>(observedAtMs),
            xykell::runtime::Vec3{static_cast<double>(x), static_cast<double>(y),
                                  static_cast<double>(z)},
            static_cast<double>(yawDegrees), static_cast<double>(metersTravelled),
            static_cast<int>(travelMethod));
        if (!o.has_value()) {
            return JNI_FALSE;
        }
        sharedObservationConsumer().consume(xykell::runtime::RuntimeObservation{*o});
        return JNI_TRUE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_observation_Observations_nativeOfferUnknown(
    JNIEnv* env, jclass, jstring eventId, jlong wireLength, jstring reason,
    jlong observedAtMs) {
    try {
        auto o = xykell::runtime::makeUnknown(
            toStd(env, eventId), static_cast<std::uint64_t>(observedAtMs),
            static_cast<std::uint64_t>(wireLength), toStd(env, reason));
        if (!o.has_value()) {
            return JNI_FALSE;
        }
        sharedObservationConsumer().consume(xykell::runtime::RuntimeObservation{*o});
        return JNI_TRUE;
    } catch (...) {
        return JNI_FALSE;
    }
}

// HUD editor bridge (Batch 8). Confined to profile HUD layouts and module
// enable flags: layout JSON round-trips through validated deserialize,
// element edits are bounds/finite/positive-scale checked, module ids are
// non-empty strings. Typed primitives/strings only; no generic bridge.
namespace {

bool loadHud(const std::string& root, const std::string& name, xykell::Profile& out,
             std::string& err) {
    xykell::ProfileManager pm(root);
    return pm.load(name, out, err);
}

bool saveHud(const std::string& root, const xykell::Profile& p, std::string& err) {
    xykell::ProfileManager pm(root);
    return pm.save(p, err);
}

bool finiteDouble(double v) {
    return v == v && v != std::numeric_limits<double>::infinity()
        && v != -std::numeric_limits<double>::infinity();
}

} // namespace

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeHud_getHudLayout(JNIEnv* env, jclass, jstring root,
                                             jstring profile) {
    try {
        xykell::Profile p;
        std::string err;
        if (!loadHud(toStd(env, root), toStd(env, profile), p, err)) {
            return nullptr;
        }
        return toJni(env, xykell::json::stringify(p.hudLayout));
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeHud_setHudLayout(JNIEnv* env, jclass, jstring root,
                                             jstring profile, jstring layoutJson) {
    try {
        const auto parsed = xykell::json::parse(toStd(env, layoutJson));
        if (!parsed.ok) {
            return JNI_FALSE;
        }
        xykell::hud::HudLayout tmp;
        std::string err;
        if (!tmp.deserialize(parsed.value, err)) {
            return JNI_FALSE;  // corrupt layout rejected, current kept
        }
        xykell::Profile p;
        if (!loadHud(toStd(env, root), toStd(env, profile), p, err)) {
            return JNI_FALSE;
        }
        p.hudLayout = parsed.value;
        return static_cast<jboolean>(saveHud(toStd(env, root), p, err));
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeHud_setHudElement(JNIEnv* env, jclass, jstring root,
                                              jstring profile, jint index, jdouble x,
                                              jdouble y, jdouble scale, jboolean visible) {
    try {
        if (index < 0 || !finiteDouble(x) || !finiteDouble(y) || !finiteDouble(scale)
            || scale <= 0.0 || scale > 10.0) {
            return JNI_FALSE;
        }
        xykell::Profile p;
        std::string err;
        if (!loadHud(toStd(env, root), toStd(env, profile), p, err)) {
            return JNI_FALSE;
        }
        xykell::hud::HudLayout layout;
        if (!layout.deserialize(p.hudLayout, err)) {
            return JNI_FALSE;
        }
        if (index >= static_cast<jint>(layout.elements.size())) {
            return JNI_FALSE;
        }
        auto& el = layout.elements[static_cast<std::size_t>(index)];
        el.x = static_cast<float>(x);
        el.y = static_cast<float>(y);
        el.scale = static_cast<float>(scale);
        el.visible = visible == JNI_TRUE;
        p.hudLayout = layout.serialize();
        return static_cast<jboolean>(saveHud(toStd(env, root), p, err));
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeHud_resetHudLayout(JNIEnv* env, jclass, jstring root,
                                               jstring profile) {
    try {
        xykell::Profile p;
        std::string err;
        if (!loadHud(toStd(env, root), toStd(env, profile), p, err)) {
            return JNI_FALSE;
        }
        xykell::hud::HudLayout dflt = xykell::hud::HudLayout::m1Default();
        p.hudLayout = dflt.serialize();
        return static_cast<jboolean>(saveHud(toStd(env, root), p, err));
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_NativeHud_setProfileModule(JNIEnv* env, jclass, jstring root,
                                                  jstring profile, jstring id,
                                                  jboolean enabled) {
    try {
        const std::string mid = toStd(env, id);
        if (mid.empty()) {
            return JNI_FALSE;
        }
        xykell::Profile p;
        std::string err;
        if (!loadHud(toStd(env, root), toStd(env, profile), p, err)) {
            return JNI_FALSE;
        }
        p.modules[mid] = (enabled == JNI_TRUE);
        return static_cast<jboolean>(saveHud(toStd(env, root), p, err));
    } catch (...) {
        return JNI_FALSE;
    }
}

// Theme picker bridge (Batch 10). Confined to the builtin theme registry:
// names + serialized token sets, read-only. The active theme is stored
// through the validated settings bridge (client.theme), never here.
JNIEXPORT jobjectArray JNICALL
Java_dev_xykell_client_NativeThemes_listThemes(JNIEnv* env, jclass) {
    jclass strCls = env->FindClass("java/lang/String");
    try {
        const auto& themes = xykell::ui::ThemeManager::builtins();
        jobjectArray arr =
            env->NewObjectArray(static_cast<jsize>(themes.size()), strCls, nullptr);
        for (jsize i = 0; i < static_cast<jsize>(themes.size()); ++i) {
            jstring s = toJni(env, themes[static_cast<std::size_t>(i)].name);
            env->SetObjectArrayElement(arr, i, s);
            env->DeleteLocalRef(s);
        }
        return arr;
    } catch (...) {
        return env->NewObjectArray(0, strCls, nullptr);
    }
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_NativeThemes_themeTokens(JNIEnv* env, jclass, jstring name) {
    try {
        xykell::ui::Theme t;
        if (!xykell::ui::ThemeManager::find(toStd(env, name), t)) {
            return nullptr;  // unknown theme: caller keeps its current palette
        }
        return toJni(env, xykell::json::stringify(t.serialize()));
    } catch (...) {
        return nullptr;
    }
}

} // extern "C"
