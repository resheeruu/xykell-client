package dev.xykell.client.runtime.network

import java.util.concurrent.ConcurrentHashMap

/**
 * Socket, thread and name-resolution policy for the relay legs.
 *
 * Pure Kotlin: no Android imports, so it is host-JVM testable and the driver
 * stays a thin applier. Three things live here, each because leaving it to the
 * platform was measurably wrong:
 *
 *  1. **Buffer sizing.** A Bedrock relay carries RakNet datagrams in both
 *     directions on one loop. The default socket buffer on Android is small
 *     enough that a burst from a real server overruns it and packets are
 *     dropped at the kernel, which the game sees as a stalled connection rather
 *     than as slow — and a stalled connection is what a user reports as
 *     "the relay doesn't work". We request a larger buffer, but we *report the
 *     granted value*, because the kernel is free to clamp it and a report that
 *     claims 2 MiB when it got 128 KiB is worse than no report.
 *
 *  2. **Thread priority.** The relay receive loop is latency-sensitive and the
 *     UI must stay responsive. One step above default is enough for the loop to
 *     win a busy-core slice; anything higher starts costing frame time, which
 *     the HUD and ClickGUI both render on. We never request a priority above
 *     [MAX_PRIORITY].
 *
 *  3. **Name resolution off the caller's thread.** This is the real bug this
 *     type exists to fix: [dev.xykell.client.runtime.relay.RelaySessionDriver]
 *     resolves its upstream host in its constructor, so starting the relay
 *     performs a blocking DNS lookup on whatever thread called it — which is
 *     the service's main thread. On a cold cache that is a multi-second
 *     main-thread stall behind a black screen. Resolution is moved behind
 *     [UpstreamResolver], which caches per host so a reconnect is instant and
 *     repeats do not re-query, and which is driven from the driver's own loop.
 *
 * Nothing here opens a socket, resolves a name, or sets a thread priority. It
 * computes values and hands them back; the platform-touching layer applies
 * them. That split is what makes the numbers testable.
 */
object RelayTuning {

    /**
     * RakNet MTU. 1492 is the IPv4 MTU minus the 20-byte IP and 8-byte UDP
     * headers, i.e. the largest datagram that will not be fragmented. Larger
     * payloads must be split by RakNet's own reliable layer, which this relay
     * already does.
     */
    const val MTU_BYTES = 1492

    /** Requested socket buffer per direction. See class docs for the caveat. */
    const val REQUESTED_BUFFER_BYTES = 2 * 1024 * 1024

    /**
     * Ceiling on what we will accept as a granted buffer. A platform that
     * reports an absurd value is reporting something we cannot act on, so the
     * number is clamped rather than surfaced as-is.
     */
    const val MAX_BUFFER_BYTES = 16 * 1024 * 1024

    /** Socket receive poll timeout, ms. One loop drives both legs. */
    const val POLL_TIMEOUT_MS = 50

    private const val BASE_PRIORITY = Thread.NORM_PRIORITY
    private const val MAX_PRIORITY = Thread.NORM_PRIORITY + 1

    /** Thread priority for the relay receive loop. See class docs. */
    fun threadPriority(): Int = BASE_PRIORITY + 1

    /**
     * Clamps a requested buffer to [MAX_BUFFER_BYTES] and floors it at the MTU,
     * because a buffer smaller than one datagram cannot hold a whole packet.
     */
    fun bufferRequest(requested: Int = REQUESTED_BUFFER_BYTES): Int =
        requested.coerceIn(MTU_BYTES, MAX_BUFFER_BYTES)

    /**
     * The value the kernel actually granted, as the caller should report it.
     *
     * A platform that refuses to report a size returns 0 or a negative; that
     * is reported as `null` (unknown) rather than as 0, because "the buffer is
     * zero bytes" and "we could not read the buffer size" are different facts
     * and only one of them is a problem.
     */
    fun bufferGranted(granted: Int): Int? =
        if (granted <= 0) null else granted.coerceAtMost(MAX_BUFFER_BYTES)

    /**
     * One-line, secret-free description of a configured pair of buffers, for the
     * relay screen and for diagnostics. Records what was asked for and what was
     * granted so a mismatch is visible instead of silent.
     */
    fun describeBuffers(requested: Int, recvGranted: Int, sendGranted: Int): String {
        val r = bufferGranted(recvGranted)
        val s = bufferGranted(sendGranted)
        val got = if (r == null || s == null) {
            "granted unknown"
        } else {
            "granted recv ${r / 1024}KiB send ${s / 1024}KiB"
        }
        return "mtu ${MTU_BYTES}B, requested ${bufferRequest(requested) / 1024}KiB, $got"
    }

    /**
     * Upstream name resolution with a positive cache.
     *
     * A resolved [java.net.InetAddress] is immutable, so caching it is safe;
     * the cache is deliberately small and TTL-free because a relay upstream is
     * a server the user picked, not a CDN node — a long-lived entry costs
     * nothing and removes a DNS round trip from every reconnect.
     *
     * Failures are **not** cached. A transient resolver failure must not be
     * sticky, so a failed lookup returns null and leaves the cache untouched.
     */
    class UpstreamResolver(
        private val lookup: (String) -> java.net.InetAddress?,
        private val capacity: Int = DEFAULT_CAPACITY,
    ) {
        init {
            require(capacity in 1..MAX_CAPACITY) { "capacity out of range" }
        }

        private val cache = ConcurrentHashMap<String, java.net.InetAddress>()

        /** Hostnames resolved through this resolver, for diagnostics only. */
        fun cachedHosts(): Set<String> = cache.keys.toSet()

        /**
         * Returns the address for [host], or null when the lookup fails. A
         * cached entry never triggers a second lookup.
         */
        fun resolve(host: String): java.net.InetAddress? {
            if (host.isEmpty() || host.length > MAX_HOST) return null
            cache[host]?.let { return it }
            val resolved = try {
                lookup(host)
            } catch (e: Exception) {
                // Resolver failures are IO errors; they must not escape as throws.
                null
            } ?: return null
            if (cache.size >= capacity && !cache.containsKey(host)) {
                // Bounded: drop an arbitrary entry rather than growing without
                // limit. A relay has one upstream, so this is not a hot path.
                cache.keys.firstOrNull()?.let { cache.remove(it) }
            }
            cache[host] = resolved
            return resolved
        }

        fun clear() = cache.clear()

        private companion object {
            const val DEFAULT_CAPACITY = 16
            const val MAX_CAPACITY = 256
            const val MAX_HOST = 253 // RFC 1035 max name length
        }
    }
}