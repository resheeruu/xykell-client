// JNI bridge: launcher UI -> the SAME ProfileManager implementation the
// native game module uses (shared code, no duplication). Storage roots differ
// per sandbox (documented in LAUNCHER-INTEGRATION.md): this bridge serves the
// launcher's own store; the game-process store syncs via export/import files.
#include <jni.h>

#include <string>
#include <vector>

#include "xykell/profile_manager.h"
#include "xykell/detection.h"
#include "xykell/version_adapter.h"
#include "xykell/runtime_provider.h"

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

} // extern "C"
