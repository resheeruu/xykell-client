// JNI bridge: launcher UI -> the SAME ProfileManager implementation the
// native game module uses (shared code, no duplication). Storage roots differ
// per sandbox (documented in LAUNCHER-INTEGRATION.md): this bridge serves the
// launcher's own store; the game-process store syncs via export/import files.
#include <jni.h>

#include <string>
#include <vector>

#include "xykell/profile_manager.h"
#include "xykell/version_adapter.h"

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
                                                   jstring abi) {
    const auto r = xykell::VersionAdapter::check(toStd(env, version), toStd(env, abi));
    std::string state;
    switch (r.state) {
        case xykell::SupportState::Supported: state = "SUPPORTED"; break;
        case xykell::SupportState::Partial: state = "PARTIAL"; break;
        case xykell::SupportState::Unsupported: state = "UNSUPPORTED"; break;
    }
    return toJni(env, state + "|" + r.reason);
}

} // extern "C"
