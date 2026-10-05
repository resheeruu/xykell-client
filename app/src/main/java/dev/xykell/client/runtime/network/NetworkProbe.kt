package dev.xykell.client.runtime.network

import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Latency history and an opt-in reachability prober. Deliberately free of
 * Android imports so it is unit-testable on the host JVM; the Android layer
 * supplies connectivity status separately in [NetworkDiagnostics].
 *
 * Two rules hold throughout:
 *  - no invented numbers. A latency is reported only when a real round trip
 *    completed; otherwise it is null, never a placeholder.
 *  - the probe targets a host the user chose. Xykell ships no implicit
 *    beacon, resolves nothing until asked, and never contacts a game
 *    endpoint. It is a TCP connect only: no payload, no response body.
 */
object NetworkProbe {

    data class Probe(
        val host: String,
        val port: Int,
        /** Round-trip in ms, or null when the attempt did not complete. */
        val latencyMs: Long?,
        val reachable: Boolean,
        val error: String?,
        val atMs: Long,
    ) {
        val ok: Boolean get() = reachable && latencyMs != null
    }

    /** Bounded latency history for the graph. Fixed capacity, oldest evicted. */
    class LatencyHistory(val capacity: Int = DEFAULT_HISTORY_CAPACITY) {
        init {
            require(capacity in 1..MAX_HISTORY_CAPACITY) { "capacity out of range" }
        }
        private val values = ArrayDeque<Long>()

        @Synchronized
        fun add(ms: Long) {
            if (ms < 0) return // never record a nonsense sample
            values.addLast(ms)
            while (values.size > capacity) values.removeFirst()
        }

        @Synchronized
        fun samples(): List<Long> = values.toList()

        @Synchronized
        fun latest(): Long? = values.lastOrNull()

        @Synchronized
        fun average(): Long? =
            if (values.isEmpty()) null else values.sum() / values.size

        @Synchronized
        fun min(): Long? = values.minOrNull()

        @Synchronized
        fun max(): Long? = values.maxOrNull()

        @Synchronized
        fun clear() = values.clear()
    }


    /**
     * Reachability + round-trip to a user-supplied host, off the main thread.
     *
     * Resolution and connect are both time-bounded so a black-holed host
     * cannot hang the app, and concurrent calls are coalesced rather than
     * opening a second socket.
     */
    class Prober(
        private val clock: () -> Long = System::currentTimeMillis,
        private val scheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "xykell-net-probe").apply { isDaemon = true }
            },
    ) {
        private val history = LatencyHistory()
        private var inFlight = false

        fun history(): LatencyHistory = history

        fun close() {
            scheduler.shutdownNow()
        }

        fun probe(
            host: String,
            port: Int,
            timeoutMs: Int = DEFAULT_TIMEOUT_MS,
            onResult: (Probe) -> Unit,
        ) {
            if (host.isBlank() || host.length > MAX_HOST) {
                onResult(Probe(host, port, null, false, "invalid host", clock()))
                return
            }
            if (port !in 1..65535) {
                onResult(Probe(host, port, null, false, "invalid port", clock()))
                return
            }
            synchronized(this) {
                if (inFlight) {
                    onResult(Probe(host, port, null, false, "probe already running", clock()))
                    return
                }
                inFlight = true
            }
            scheduler.execute {
                val result = measure(host, port, timeoutMs)
                if (result.ok) history.add(result.latencyMs!!)
                synchronized(this) { inFlight = false }
                onResult(result)
            }
        }

        private fun measure(host: String, port: Int, timeoutMs: Int): Probe {
            val started = clock()
            return try {
                val addr = InetAddress.getByName(host)
                val socket = java.net.Socket()
                try {
                    socket.connect(java.net.InetSocketAddress(addr, port), timeoutMs)
                    Probe(host, port, clock() - started, true, null, clock())
                } finally {
                    runCatching { socket.close() }
                }
            } catch (e: Exception) {
                // The class name is safe to show; an exception message could
                // echo a resolver detail or an internal path, so it is dropped.
                Probe(
                    host, port, null, false,
                    e.javaClass.simpleName.ifEmpty { "failed" }, clock(),
                )
            }
        }
    }

    const val DEFAULT_HISTORY_CAPACITY = 60
    private const val MAX_HISTORY_CAPACITY = 600
    const val DEFAULT_TIMEOUT_MS = 3000
    const val MIN_PERIOD_MS = 2000L
    private const val MAX_HOST = 253

    /** Builds a periodic probe task the caller owns and must cancel. */
    fun scheduleProbes(
        prober: Prober,
        host: String,
        port: Int,
        periodMs: Long,
        onResult: (Probe) -> Unit,
    ): ScheduledExecutorService {
        val s = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "xykell-net-sched").apply { isDaemon = true }
        }
        s.scheduleWithFixedDelay(
            { runCatching { prober.probe(host, port, DEFAULT_TIMEOUT_MS, onResult) } },
            0L, periodMs.coerceAtLeast(MIN_PERIOD_MS), TimeUnit.MILLISECONDS,
        )
        return s
    }
}
