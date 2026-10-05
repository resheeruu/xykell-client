package dev.xykell.client.runtime.observation

/**
 * Minimal field-only JNI offer boundary into the native
 * ObservationConsumer (Stage 12). Primitives only: no JSON, no byte
 * arrays, no sockets, no crypto objects, no keys, no ciphertext, no
 * commands. Invalid observations are rejected natively (never stored).
 * Calls are exception-safe: native failure surfaces as dropped offers.
 */
object Observations {
    init {
        System.loadLibrary("xykellcore")
    }

    @JvmStatic
    external fun nativeOfferPlayerMessage(
        eventId: String,
        observedAtMs: Long,
        sender: String,
        message: String,
    ): Boolean

    @JvmStatic
    external fun nativeOfferPlayerTravelled(
        eventId: String,
        observedAtMs: Long,
        x: Double,
        y: Double,
        z: Double,
        yawDegrees: Double,
        metersTravelled: Double,
        travelMethod: Int,
    ): Boolean

    @JvmStatic
    external fun nativeOfferUnknown(
        eventId: String,
        wireLength: Long,
        reason: String,
        observedAtMs: Long,
    ): Boolean

    fun offerPlayerMessage(eventId: String, observedAtMs: Long, sender: String, message: String) {
        try {
            nativeOfferPlayerMessage(eventId, observedAtMs, sender, message)
        } catch (e: UnsatisfiedLinkError) {
            // Native bridge absent: the offer is dropped and the counts stay
            // local, but the failure is recorded so diagnostics can say why.
            dev.xykell.client.runtime.NativeBridgeStatus.recordFailure(
                "Observations", "nativeOfferPlayerMessage",
            )
        }
    }

    fun offerPlayerTravelled(
        eventId: String,
        observedAtMs: Long,
        x: Double,
        y: Double,
        z: Double,
        yawDegrees: Double,
        metersTravelled: Double,
        travelMethod: Int,
    ) {
        try {
            nativeOfferPlayerTravelled(
                eventId, observedAtMs, x, y, z, yawDegrees, metersTravelled, travelMethod,
            )
        } catch (e: UnsatisfiedLinkError) {
            dev.xykell.client.runtime.NativeBridgeStatus.recordFailure(
                "Observations", "nativeOfferPlayerTravelled",
            )
        }
    }

    fun offerUnknown(eventId: String, wireLength: Long, reason: String, observedAtMs: Long) {
        try {
            nativeOfferUnknown(eventId, wireLength, reason, observedAtMs)
        } catch (e: UnsatisfiedLinkError) {
            dev.xykell.client.runtime.NativeBridgeStatus.recordFailure(
                "Observations", "nativeOfferUnknown",
            )
        }
    }
}
