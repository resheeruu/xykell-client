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
#include "xykell/runtime_observation_consumer.h"

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

} // extern "C"
