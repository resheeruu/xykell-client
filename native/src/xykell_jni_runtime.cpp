// JNI bridge, part 2: the read-only runtime substrate and the observation
// sink. See native/src/xykell_jni.cpp for the shared invariants; the rules
// are identical here.
//
// Nothing in this file can transmit, mutate, or forge gameplay state. The
// observation side is a SINK: it accepts already-validated model objects and
// stores them in a bounded snapshot. There is no request, no send, no
// subscribe-from-native, and no game control anywhere in the boundary.
#include <jni.h>

#include <cmath>
#include <string>
#include <vector>

#include "xykell/json_min.h"
#include "xykell/lan_discovery.h"
#include "xykell/runtime_event_observation.h"
#include "xykell/runtime_observation_consumer.h"
#include "xykell/runtime_provider.h"
#include "xykell/runtime_session.h"

namespace {

std::string fromJ(JNIEnv* env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring toJ(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

jboolean jbool(bool v) { return v ? JNI_TRUE : JNI_FALSE; }

constexpr std::size_t kMaxNameLen = 64;
constexpr std::size_t kMaxTextLen = 512;

bool okName(const std::string& n) {
    return !n.empty() && n.size() <= kMaxNameLen && n.find('/') == std::string::npos &&
           n.find('\\') == std::string::npos && n.find("..") == std::string::npos;
}

bool okText(const std::string& t) { return t.size() <= kMaxTextLen; }

bool okReason(const std::string& t) { return t.size() <= kMaxTextLen; }

const char* providerStateName(xykell::runtime::ProviderState s) {
    switch (s) {
        case xykell::runtime::ProviderState::Stopped: return "STOPPED";
        case xykell::runtime::ProviderState::Starting: return "STARTING";
        case xykell::runtime::ProviderState::Running: return "RUNNING";
        case xykell::runtime::ProviderState::Stopping: return "STOPPING";
        case xykell::runtime::ProviderState::Failed: return "FAILED";
    }
    return "STOPPED";
}

const char* sessionAvailabilityName(xykell::runtime::Availability a) {
    switch (a) {
        case xykell::runtime::Availability::Unknown: return "UNKNOWN";
        case xykell::runtime::Availability::Unavailable: return "UNAVAILABLE";
        case xykell::runtime::Availability::Available: return "AVAILABLE";
    }
    return "UNKNOWN";
}

// One provider instance per process, with the selected one addressable.
// Defaults to the synthetic relay, which the C++ tests already exercise.
xykell::runtime::SyntheticRelayProvider& relay() {
    static xykell::runtime::SyntheticRelayProvider p;
    return p;
}

xykell::runtime::LanDiscoveryProvider& lan() {
    static xykell::runtime::LanDiscoveryProvider p;
    return p;
}

xykell::runtime::NativeProviderStub& nativeStub() {
    static xykell::runtime::NativeProviderStub p;
    return p;
}

xykell::runtime::RuntimeProvider* selected() {
    static std::string which = xykell::runtime::SyntheticRelayProvider::kName;
    if (which == xykell::runtime::LanDiscoveryProvider::kName) {
        return &lan();
    }
    if (which == xykell::runtime::NativeProviderStub::kName) {
        return &nativeStub();
    }
    return &relay();
}

std::string& selectedName() {
    static std::string which = xykell::runtime::SyntheticRelayProvider::kName;
    return which;
}

xykell::runtime::SessionManager& sessionMgr() {
    static xykell::runtime::SessionManager mgr;
    return mgr;
}

xykell::runtime::ObservationConsumer& obsConsumer() {
    static xykell::runtime::ObservationConsumer c;
    return c;
}

} // namespace

extern "C" {

// ===========================================================================
// dev.xykell.client.runtime.RuntimeStatus   (@JvmStatic -> static methods)
// ===========================================================================

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeStart(JNIEnv*, jclass) {
    return jbool(selected()->start());
}

JNIEXPORT void JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeStop(JNIEnv*, jclass) {
    selected()->stop();
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeSelectProvider(JNIEnv* env, jclass,
                                                                  jstring jname) {
    const std::string name = fromJ(env, jname);
    if (name != xykell::runtime::SyntheticRelayProvider::kName &&
        name != xykell::runtime::LanDiscoveryProvider::kName &&
        name != xykell::runtime::NativeProviderStub::kName) {
        return JNI_FALSE; // unknown provider: nothing selected, nothing changed
    }
    // Stop the outgoing one before switching, so two providers never run at once.
    selected()->stop();
    selectedName() = name;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeStatus(JNIEnv* env, jclass) {
    auto* p = selected();
    const auto diag = p->diagnostics();
    const auto& sess = sessionMgr().current();
    xykell::json::Object o;
    o.emplace("state", xykell::json::Value(std::string(providerStateName(diag.state))));
    o.emplace("provider", xykell::json::Value(std::string(p->name())));
    o.emplace("lastError", xykell::json::Value(diag.lastError));
    o.emplace("sessionActive", xykell::json::Value(sessionMgr().active()));
    o.emplace("sessionId", xykell::json::Value(sess.sessionId));
    o.emplace("sessionProvider", xykell::json::Value(sess.providerName));
    // Connection is never claimed. The C++ session leaves this false and the
    // reason string is surfaced verbatim.
    o.emplace("connected", xykell::json::Value(sess.connected));
    // The reason is the C++ session's own diagnostics string, verbatim.
    o.emplace("connectionReason", xykell::json::Value(sess.diagnostics));
    o.emplace("diagnostics", xykell::json::Value(sess.diagnostics));
    // Game state is reported field by field with each field's own
    // availability, so "unknown" is never flattened into a fake value.
    const auto& g = sess.gameState;
    xykell::json::Object gs;
    gs.emplace("position",
               xykell::json::Value(std::string(sessionAvailabilityName(g.position.availability))));
    gs.emplace("health",
               xykell::json::Value(std::string(sessionAvailabilityName(g.health.availability))));
    o.emplace("gameState", xykell::json::Value(std::move(gs)));
    o.emplace("minecraftRuntime", xykell::json::Value(std::string("NOT CONNECTED")));
    return toJ(env, xykell::json::stringify(xykell::json::Value(std::move(o))));
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeCapabilities(JNIEnv* env, jclass) {
    // A plain JSON array of strings, which is what RuntimeStatus.summary()
    // counts with a quote tally.
    xykell::json::Array arr;
    for (const auto& cap : selected()->capabilities()) {
        arr.push_back(xykell::json::Value(cap.name));
    }
    return toJ(env, xykell::json::stringify(xykell::json::Value(std::move(arr))));
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeEndpoints(JNIEnv* env, jclass) {
    // Objects carrying "id", which summary() splits on.
    xykell::json::Array arr;
    for (const auto& ep : relay().discover()) {
        xykell::json::Object o;
        o.emplace("id", xykell::json::Value(ep.id));
        o.emplace("displayName", xykell::json::Value(ep.displayName));
        o.emplace("address", xykell::json::Value(ep.address));
        o.emplace("port", xykell::json::Value(static_cast<double>(ep.port)));
        arr.push_back(xykell::json::Value(std::move(o)));
    }
    return toJ(env, xykell::json::stringify(xykell::json::Value(std::move(arr))));
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeDiscovery(JNIEnv* env, jclass) {
    xykell::json::Object o;
    o.emplace("running",
              xykell::json::Value(lan().state() ==
                                   xykell::runtime::ProviderState::Running));
    o.emplace("provider", xykell::json::Value(std::string(lan().name())));
    o.emplace("lastError", xykell::json::Value(lan().diagnostics().lastError));
    o.emplace("selected", xykell::json::Value(selectedName()));
    return toJ(env, xykell::json::stringify(xykell::json::Value(std::move(o))));
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeSelectEndpoint(JNIEnv* env, jclass,
                                                                 jstring jid) {
    const std::string id = fromJ(env, jid);
    if (!okName(id)) {
        return JNI_FALSE;
    }
    std::string sessionId;
    // Opening a session to an advertised synthetic endpoint. This is a
    // loopback in-memory session, not a game connection.
    return jbool(relay().openSession(id, &sessionId));
}

JNIEXPORT jstring JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeBeginSession(JNIEnv* env, jclass,
                                                                jstring jpkg, jstring jver) {
    const std::string pkg = fromJ(env, jpkg);
    const std::string version = fromJ(env, jver);
    if (!okName(pkg) || !okName(version)) {
        return toJ(env, "{}");
    }
    const auto s = sessionMgr().begin(pkg, version, selectedName());
    xykell::json::Object o;
    o.emplace("sessionId", xykell::json::Value(s.sessionId));
    o.emplace("package", xykell::json::Value(s.minecraftPackage));
    o.emplace("version", xykell::json::Value(s.minecraftVersion));
    o.emplace("provider", xykell::json::Value(s.providerName));
    o.emplace("launched", xykell::json::Value(s.launched));
    o.emplace("connected", xykell::json::Value(s.connected));
    o.emplace("reason", xykell::json::Value(s.diagnostics));
    o.emplace("diagnostics", xykell::json::Value(s.diagnostics));
    return toJ(env, xykell::json::stringify(xykell::json::Value(std::move(o))));
}

JNIEXPORT void JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeMarkLaunched(JNIEnv*, jclass) {
    sessionMgr().markLaunched();
}

JNIEXPORT void JNICALL
Java_dev_xykell_client_runtime_RuntimeStatus_nativeEndSession(JNIEnv* env, jclass,
                                                              jstring jreason) {
    std::string reason = fromJ(env, jreason);
    if (!okReason(reason)) {
        reason = "unspecified"; // bounded: an unbounded reason is never stored
    }
    sessionMgr().end(reason);
}

// ===========================================================================
// dev.xykell.client.runtime.observation.Observations  (@JvmStatic)
//
// A SINK. Every argument is validated by the existing C++ factory functions
// (makePlayerMessage / makePlayerTravel / makeUnknown), which return nullopt
// for invalid input, so nothing unchecked reaches the snapshot.
// ===========================================================================

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_observation_Observations_nativeOfferPlayerMessage(
    JNIEnv* env, jclass, jstring jeventId, jlong jatMs, jstring jsender, jstring jmessage) {
    const std::string eventId = fromJ(env, jeventId);
    const std::string sender = fromJ(env, jsender);
    const std::string message = fromJ(env, jmessage);
    if (!okName(eventId) || !okText(sender) || !okText(message) || jatMs < 0) {
        return JNI_FALSE;
    }
    const auto obs = xykell::runtime::makePlayerMessage(
        eventId, static_cast<std::uint64_t>(jatMs), sender, message);
    if (!obs.has_value()) {
        return JNI_FALSE;
    }
    obsConsumer().consume(xykell::runtime::PlayerMessageObservation{*obs});
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_observation_Observations_nativeOfferPlayerTravelled(
    JNIEnv* env, jclass, jstring jeventId, jlong jatMs, jdouble x, jdouble y, jdouble z,
    jdouble jyaw, jdouble jmeters, jint jmethod) {
    const std::string eventId = fromJ(env, jeventId);
    if (!okName(eventId) || jatMs < 0) {
        return JNI_FALSE;
    }
    // The factory rejects non-finite coordinates and a negative distance, so a
    // NaN can never reach the HUD through this boundary.
    xykell::runtime::Vec3 pos;
    pos.x = x;
    pos.y = y;
    pos.z = z;
    const auto obs = xykell::runtime::makePlayerTravel(
        eventId, static_cast<std::uint64_t>(jatMs), pos, jyaw, jmeters, jmethod);
    if (!obs.has_value()) {
        return JNI_FALSE;
    }
    obsConsumer().consume(xykell::runtime::PlayerTravelObservation{*obs});
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_xykell_client_runtime_observation_Observations_nativeOfferUnknown(
    JNIEnv* env, jclass, jstring jeventId, jlong jwireLength, jstring jreason, jlong jatMs) {
    const std::string eventId = fromJ(env, jeventId);
    const std::string reason = fromJ(env, jreason);
    if (!okName(eventId) || !okReason(reason) || jatMs < 0 || jwireLength < 0) {
        return JNI_FALSE;
    }
    // Unknown frames are counted with metadata only; no payload is retained.
    const auto obs = xykell::runtime::makeUnknown(
        eventId, static_cast<std::uint64_t>(jatMs),
        static_cast<std::uint64_t>(jwireLength), reason);
    if (!obs.has_value()) {
        return JNI_FALSE;
    }
    obsConsumer().consume(xykell::runtime::UnknownObservation{*obs});
    return JNI_TRUE;
}

} // extern "C"
