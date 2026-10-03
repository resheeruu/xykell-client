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

    fun start(): Boolean = guard { nativeStart() } ?: false

    fun stop() {
        try {
            nativeStop()
        } catch (e: UnsatisfiedLinkError) {
            // Native bridge unavailable: nothing to stop.
        }
    }

    /** Short honest multi-line summary for status surfaces. */
    fun summary(): String {
        val st = guard { JSONObject(nativeStatus()) }
            ?: return "Runtime: unavailable (native bridge missing)"
        val state = st.optString("state", "?")
        val provider = st.optString("provider", "?").ifEmpty { "?" }
        val caps = guard { nativeCapabilities() } ?: "[]"
        val eps = guard { nativeEndpoints() } ?: "[]"
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
            "Minecraft Runtime: NOT CONNECTED\nLast Diagnostic: $lastDiag"
    }

    fun providerUnavailableReason(): String =
        "Native Provider Unavailable (lab-gated). Protocol Not Configured."

    private inline fun <T> guard(block: () -> T): T? {
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            null
        } catch (e: org.json.JSONException) {
            null
        }
    }
}
