package dev.xykell.client.runtime.observation

/**
 * Bounded, in-memory state assembled from the read-only observation stream.
 *
 * This is deliberately *not* a game client. It holds only what the translator
 * was actually asked to observe, and every field is either a real observed
 * value or `null`. A field is never derived, interpolated, or estimated.
 *
 * Two sources exist today:
 *   - `PlayerMessage`  — observed in the lab (sender, receiver, message, time)
 *   - `PlayerTravelled` — translated and subscribed; x/y/z, yaw, distance,
 *                         travel method
 *
 * Anything not in that list has no state here at all, so no consumer can
 * render a value the observation layer never received.
 */
class ObservedState(private val clock: () -> Long = System::currentTimeMillis) {

    data class ChatLine(
        val eventId: String,
        val atMs: Long,
        val sender: String,
        val message: String,
    )

    data class Motion(
        val eventId: String,
        val atMs: Long,
        val x: Double?,
        val y: Double?,
        val z: Double?,
        val yawDegrees: Double?,
        val metersTravelled: Double?,
        val travelMethod: Int?,
    ) {
        val anyKnown: Boolean get() = x != null || yawDegrees != null
    }

    /** Metres per second, derived only from two observed travel samples. */
    data class Speed(val metresPerSecond: Double, val sampledAtMs: Long, val samples: Int)

    val chat: List<ChatLine> get() = synchronized(this) { chatList.toList() }
    val motion: Motion? get() = synchronized(this) { latestMotion }

    private val chatList = ArrayDeque<ChatLine>()
    private var chatIds = LinkedHashSet<String>()
    private var latestMotion: Motion? = null
    private var lastTravel: Pair<Long, Double>? = null
    private var lastSpeed: Speed? = null

    var chatCap: Int = MAX_CHAT
        set(value) {
            require(value in 0..MAX_CHAT_CAP) { "chatCap out of range" }
            field = value
            trimChat()
        }

    @Synchronized
    fun onPlayerMessage(eventId: String, atMs: Long, sender: String, message: String): Boolean {
        if (eventId.isEmpty() || eventId.length > MAX_ID) return false
        // Bounded memory, and duplicate suppression so a redelivery is not
        // shown twice.
        if (!chatIds.add(eventId)) return false
        chatList.addLast(ChatLine(eventId, atMs, sender.take(MAX_TEXT), message.take(MAX_TEXT)))
        trimChat()
        return true
    }

    private fun trimChat() {
        while (chatList.size > chatCap) {
            chatList.removeFirst()
            if (chatList.isNotEmpty()) chatIds.remove(chatList.first().eventId)
        }
        if (chatList.isEmpty()) chatIds.clear()
    }

    @Synchronized
    fun onPlayerTravelled(
        eventId: String,
        atMs: Long,
        x: Double?,
        y: Double?,
        z: Double?,
        yawDegrees: Double?,
        metersTravelled: Double?,
        travelMethod: Int?,
    ): Boolean {
        if (eventId.isEmpty() || eventId.length > MAX_ID) return false
        latestMotion = Motion(
            eventId, atMs, finite(x), finite(y), finite(z), finite(yawDegrees),
            finite(metersTravelled), travelMethod,
        )
        val dist = finite(metersTravelled)
        if (dist != null) {
            val prev = lastTravel
            val now = clock()
            // Two samples are needed before a rate means anything; a single
            // point would be a guess, so it stays null.
            if (prev != null && now > prev.first && dist >= prev.second) {
                val dtSec = (now - prev.first) / 1000.0
                lastSpeed = Speed((dist - prev.second) / dtSec, now, 2)
            }
            lastTravel = now to dist
        }
        return true
    }

    val speed: Speed? get() = synchronized(this) { lastSpeed }

    @Synchronized
    fun clear() {
        chatList.clear()
        chatIds.clear()
        latestMotion = null
        lastTravel = null
        lastSpeed = null
    }

    val chatCount: Int get() = synchronized(this) { chatList.size }

    /** NaN/Inf from a malformed frame must never reach the HUD. */
    private fun finite(v: Double?): Double? = v?.takeIf { it.isFinite() }

    private companion object {
        const val MAX_CHAT = 200
        const val MAX_CHAT_CAP = 2000
        const val MAX_ID = 128
        const val MAX_TEXT = 512
    }
}
