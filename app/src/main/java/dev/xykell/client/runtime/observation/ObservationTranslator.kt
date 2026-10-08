package dev.xykell.client.runtime.observation

import org.json.JSONObject

/**
 * Production translator: verified envelope -> normalized observation
 * fields. Mirrors lab/bedrock-websocket/translate.mjs SEMANTICS
 * exactly (same validation, same field mapping, same Unknown/Invalid
 * split); it is not a second interpretation. No network, no crypto,
 * no I/O. Pure JVM (org.json ships with Android; host tests cover it).
 *
 * Output carries the Stage-10 field set only; JNI offers consume these
 * primitives (see Observations). Unknown carries metadata only.
 */
sealed interface Translated {
    val eventId: String
    val observedAtMs: Long
}

data class ChatMessage(
    override val eventId: String,
    override val observedAtMs: Long,
    val sender: String,
    val message: String,
) : Translated

data class Travelled(
    override val eventId: String,
    override val observedAtMs: Long,
    val x: Double,
    val y: Double,
    val z: Double,
    val yawDegrees: Double,
    val metersTravelled: Double,
    /** Raw observed int (0 and 2 seen); semantics unverified, never mapped. */
    val travelMethod: Int,
) : Translated

/**
 * Observed vitals, straight off the wire.
 *
 * [health] and [timeTicks] are null when that packet was not part of *this*
 * observation, never zero — SetHealth 0x2A and SetTime 0x0A arrive on
 * independent cadences, so a clock-only observation must not blank the health
 * the HUD is already showing. Units are the server's own (health arrives as 20
 * for a full bar); no maximum is invented, because the server never states one.
 */
data class Vitals(
    override val eventId: String,
    override val observedAtMs: Long,
    val health: Int?,
    val timeTicks: Int?,
) : Translated

/**
 * How many entities the relay currently tracks.
 *
 * A count of what the relay has been *told about*, never a claim about the
 * world: an entity the server never announced is absent here, and that is the
 * honest reading rather than a defect.
 */
data class Population(
    override val eventId: String,
    override val observedAtMs: Long,
    val entityCount: Int,
    val playerCount: Int,
) : Translated

data class UnknownFrame(
    override val eventId: String,
    override val observedAtMs: Long,
    val wireLength: Int,
    val reason: String,
) : Translated

object ObservationTranslator {
    const val REASON_UNRECOGNIZED = "unrecognized-event"
    const val REASON_NON_EVENT = "non-event-envelope"
    const val REASON_UNPARSED = "unparsed-input"

    private fun finite(v: Double): Boolean = v.isFinite()

    /**
     * Returns null for Invalid (malformed/unusable) input. Well-formed
     * but unproven envelopes yield UnknownFrame, never null.
     */
    fun translate(
        envelope: JSONObject?,
        eventId: String,
        observedAtMs: Long,
        wireLength: Int,
    ): Translated? {
        if (eventId.isEmpty() || envelope == null) return null
        val header = envelope.optJSONObject("header") ?: return null
        if (header.optString("messagePurpose", "") == "event") {
            when (header.optString("eventName", "")) {
                "PlayerMessage" -> return chat(envelope, eventId, observedAtMs)
                "PlayerTravelled" -> return travel(envelope, eventId, observedAtMs)
                else -> {
                    if (header.optString("eventName", "").isEmpty()) return null
                    return UnknownFrame(eventId, observedAtMs, wireLength, REASON_UNRECOGNIZED)
                }
            }
        }
        if (header.optString("messagePurpose", "").isNotEmpty() ||
            header.optString("messageType", "").isNotEmpty()
        ) {
            return UnknownFrame(eventId, observedAtMs, wireLength, REASON_NON_EVENT)
        }
        return null
    }

    private fun chat(envelope: JSONObject, eventId: String, atMs: Long): Translated? {
        val body = envelope.optJSONObject("body") ?: return null
        val sender = body.optString("sender", "")
        // Exact preservation: no normalization of any kind.
        val message = if (body.has("message")) body.optString("message", "") else ""
        if (sender.isEmpty() || message.isEmpty()) return null
        // receiver deliberately absent (Stage-10: unproven as signal).
        return ChatMessage(eventId, atMs, sender, message)
    }

    private fun travel(envelope: JSONObject, eventId: String, atMs: Long): Translated? {
        val body = envelope.optJSONObject("body") ?: return null
        val player = body.optJSONObject("player") ?: return null
        val pos = player.optJSONObject("position") ?: return null
        val x = pos.optDouble("x", Double.NaN)
        val y = pos.optDouble("y", Double.NaN)
        val z = pos.optDouble("z", Double.NaN)
        val yaw = player.optDouble("yRot", Double.NaN)
        val meters = body.optDouble("metersTravelled", Double.NaN)
        // Integral raw method only (JNI Int boundary); non-integral is Invalid,
        // stricter than the lab by necessity and documented as such.
        val methodRaw = body.optDouble("travelMethod", Double.NaN)
        if (!finite(x) || !finite(y) || !finite(z) || !finite(yaw)) return null
        if (!finite(meters) || meters < 0.0) return null
        if (!finite(methodRaw) || methodRaw != kotlin.math.floor(methodRaw)) return null
        return Travelled(eventId, atMs, x, y, z, yaw, meters, methodRaw.toInt())
    }
}
