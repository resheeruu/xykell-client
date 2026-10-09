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

    /** Sentinel for "this observation did not carry the field"; never a value. */
    const val ABSENT = -1

    @JvmStatic
    external fun nativeOfferPlayerMessage(
        eventId: String,
        observedAtMs: Long,
        sender: String,
        message: String,
    ): Boolean

    /** Native consumer counters ("msgs=N travels=N ..."). Empty when the
     *  bridge is unavailable; never carries message content. */
    @JvmStatic
    external fun nativeObservationStats(): String

    fun stats(): String = try {
        nativeObservationStats()
    } catch (e: UnsatisfiedLinkError) {
        dev.xykell.client.runtime.NativeBridgeStatus.recordFailure(
            "Observations", "nativeObservationStats",
        )
        ""
    }

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

    // Parameter order must match bridge.cpp: (eventId, wireLength, reason,
    // observedAtMs). JNI links by name only, so a reorder compiles but
    // swaps arguments at the call boundary.
    /**
     * Observed vitals (SetHealth 0x2A / SetTime 0x0A).
     *
     * -1 means "not part of this observation" and maps to null, NOT to zero.
     * That distinction is the whole reason this exists as a separate offer: the
     * two packets arrive on different cadences, and collapsing "not seen" into
     * "zero" would empty the health readout on the first clock tick.
     */
    @JvmStatic
    external fun nativeOfferVitals(
        eventId: String,
        observedAtMs: Long,
        health: Int,
        timeTicks: Int,
    ): Boolean

    /** Observed entity counts. Negative counts are rejected natively. */
    /**
     * One add/remove of an online player from PlayerList 0x3f.
     *
     * `present` is explicit rather than inferred from an empty name: a removal
     * carries no name, and treating "no name" as "a join with a blank name"
     * would put an empty row on the tab list.
     */
    @JvmStatic
    external fun nativeOfferPlayerList(
        eventId: String,
        observedAtMs: Long,
        present: Boolean,
        uuid: String,
        name: String,
    ): Boolean

    @JvmStatic
    external fun nativeOfferPopulation(
        eventId: String,
        observedAtMs: Long,
        entityCount: Int,
        playerCount: Int,
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

    /** Offer observed vitals; -1 marks a field this packet did not carry. */
    fun offerVitals(eventId: String, observedAtMs: Long, health: Int?, timeTicks: Int?) {
        try {
            nativeOfferVitals(
                eventId,
                observedAtMs,
                health ?: ABSENT,
                timeTicks ?: ABSENT,
            )
        } catch (e: UnsatisfiedLinkError) {
            dev.xykell.client.runtime.NativeBridgeStatus.recordFailure(
                "Observations", "nativeOfferVitals",
            )
        }
    }

    /** Offer a PlayerList add/remove. [name] is ignored when [present] is false. */
    fun offerPlayerList(
        eventId: String,
        observedAtMs: Long,
        present: Boolean,
        uuid: String,
        name: String,
    ) {
        try {
            nativeOfferPlayerList(eventId, observedAtMs, present, uuid, if (present) name else "")
        } catch (e: UnsatisfiedLinkError) {
            dev.xykell.client.runtime.NativeBridgeStatus.recordFailure(
                "Observations", "nativeOfferPlayerList",
            )
        }
    }

    fun offerPopulation(eventId: String, observedAtMs: Long, entityCount: Int, playerCount: Int) {
        try {
            nativeOfferPopulation(eventId, observedAtMs, entityCount, playerCount)
        } catch (e: UnsatisfiedLinkError) {
            dev.xykell.client.runtime.NativeBridgeStatus.recordFailure(
                "Observations", "nativeOfferPopulation",
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
