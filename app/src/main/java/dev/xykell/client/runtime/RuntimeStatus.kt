package dev.xykell.client.runtime

import org.json.JSONObject

/**
 * Read-only facade over the native runtime substrate (see
 * native/.../xykell_runtime_provider.cpp and docs/RUNTIME-SUBSTRATE.md).
 *
 * Exposes STATUS ONLY: state, provider, capabilities, endpoints,
 * diagnostics. There is deliberately no API here (or in the JNI layer)
 * that can transmit, mutate, or forge gameplay state.
 *
 * All calls are exception-safe: native failure surfaces as status text,
 * never a crash. Honest wording only -- "Synthetic", never "Minecraft".
 */
object RuntimeStatus {
    init {
        System.loadLibrary("xykellcore")
    }

    @JvmStatic external fun nativeStart(): Boolean
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeSelectProvider(name: String): Boolean
    @JvmStatic external fun nativeStatus(): String
    @JvmStatic external fun nativeCapabilities(): String
    @JvmStatic external fun nativeEndpoints(): String
    @JvmStatic external fun nativeDiscovery(): String
    @JvmStatic external fun nativeSelectEndpoint(id: String): Boolean
    @JvmStatic external fun nativeBeginSession(pkg: String, version: String): String
    @JvmStatic external fun nativeMarkLaunched()
    @JvmStatic external fun nativeEndSession(reason: String)

    fun start(): Boolean = guard { nativeStart() } ?: false

    fun stop() {
        try {
            nativeStop()
        } catch (e: UnsatisfiedLinkError) {
            // Native bridge unavailable: nothing to stop.
        }
    }

    fun beginSession(pkg: String, version: String): String =
        guard { nativeBeginSession(pkg, version) } ?: ""

    fun markLaunched() {
        try {
            nativeMarkLaunched()
        } catch (e: UnsatisfiedLinkError) {
        }
    }

    fun endSession(reason: String) {
        try {
            nativeEndSession(reason)
        } catch (e: UnsatisfiedLinkError) {
        }
    }

    /** Short honest multi-line summary for status surfaces. */
    fun summary(): String {
        val st = guard("nativeStatus") { JSONObject(nativeStatus()) }
            ?: return "Runtime: unavailable (native bridge missing: " +
                NativeBridgeStatus.summary() + ")"
        val state = st.optString("state", "?")
        val provider = st.optString("provider", "?").ifEmpty { "?" }
        val caps = guard("nativeCapabilities") { nativeCapabilities() } ?: "[]"
        val eps = guard("nativeEndpoints") { nativeEndpoints() } ?: "[]"
        val capCount = caps.count { it == '"' } / 2
        val epCount = eps.split("\"id\"").size - 1
        val disc = guard { JSONObject(nativeDiscovery()) }
        val discState = if (disc?.optBoolean("running", false) == true) "ACTIVE" else "IDLE"
        val selected = disc?.optString("selected", "").orEmpty().ifEmpty { "none" }
        val lastDiag = st.optString("lastError", "").ifEmpty { "none" }
        val providerLabel = when (provider) {
            "synthetic-relay" -> "Synthetic Relay"
            "lan-discovery" -> "LAN Discovery"
            "native" -> "Native Provider Unavailable"
            else -> provider
        }
        return "Runtime: $state\nProvider: $providerLabel\n" +
            "Session: SYNTHETIC\nCapabilities: $capCount\nEndpoints: $epCount\n" +
            "Discovery: $discState\nSelected: $selected\n" +
            "Minecraft Runtime: NOT CONNECTED\nLast Diagnostic: $lastDiag\n" +
            NativeBridgeStatus.summary()
    }

    fun providerUnavailableReason(): String =
        "Native Provider Unavailable (lab-gated). Protocol Not Configured."

    private const val BRIDGE = "RuntimeStatus"

    /**
     * Records an unresolved native symbol before falling back, so a broken
     * bridge reports itself instead of presenting as "no runtime detected".
     */
    private inline fun <T> guard(symbol: String = "", block: () -> T): T? {
        return try {
            val v = block()
            NativeBridgeStatus.recordSuccess(BRIDGE, if (symbol.isEmpty()) "call" else symbol)
            v
        } catch (e: UnsatisfiedLinkError) {
            NativeBridgeStatus.recordFailure(BRIDGE, if (symbol.isEmpty()) "call" else symbol)
            null
        } catch (e: org.json.JSONException) {
            null
        }
    }
}
