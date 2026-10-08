package dev.xykell.client.runtime.relay

import dev.xykell.client.runtime.observation.ObservationExternal
import dev.xykell.client.runtime.observation.Population

/**
 * Feeds the observation pipeline from a terminating session's plaintext.
 *
 * The pipe fed observation from a raw-datagram tap, which only works before a key
 * exists — and the pipe never terminates a session, so it never got one:
 * `RelayService` skipped every handshake event and the translator stayed inert.
 * A [RelaySession] hands out decrypted packets, so the same [TapTranslator]
 * runs against real traffic for the first time.
 *
 * Pure pass-through with a side effect: it observes, then delegates, and it
 * observes the packet *before* the modules touch it. That ordering is
 * deliberate — observation reports what the server actually sent, not what a
 * module turned it into, so `SetHealth 0x2A` dropped by `disabler` is still
 * reported. An observer that threw would kill the relay, so it is guarded.
 */
class RelayObservation(
    private val delegate: RelayListener,
    private val translator: TapTranslator = TapTranslator(),
    /** The runtime's own table, so the reported count is the live one. */
    private val entities: EntityTable,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : RelayListener {

    /** Reported entity counts are throttled: one line per interval, not per packet. */
    var populationIntervalMs: Long = DEFAULT_POPULATION_INTERVAL_MS
    private var lastPopulationAtMs = Long.MIN_VALUE

    /** Live counters and last position, for the session screen. */
    val snapshot: TapTranslator.Snapshot get() = translator.snapshot

    override fun transform(direction: RelayDirection, packet: ByteArray): List<ByteArray> {
        // fromClient in TapTranslator means "the game sent this", which is the
        // server-bound leg; that is the only side that proves the self id.
        val event = TapTranslator.Event.Batch(
            fromClient = direction == RelayDirection.TO_SERVER,
            packets = listOf(packet),
        )
        try {
            for (item in translator.onEvent(event, nowMs())) {
                ObservationExternal.offer(item)
            }
        } catch (e: Exception) {
            // Observation is best-effort by contract; forwarding is not.
        }
        val out = delegate.transform(direction, packet)
        maybeReportPopulation()
        return out
    }

    /**
     * Emit a population observation at most once per [populationIntervalMs].
     *
     * Throttled because the count changes on entity packets, and an unthrottled
     * report would be one observation per add/move — hundreds per second for a
     * busy server, all carrying the same two numbers. Deliberately reported on
     * the packet path rather than a timer so it needs no thread of its own.
     */
    private fun maybeReportPopulation() {
        val now = nowMs()
        if (lastPopulationAtMs != Long.MIN_VALUE &&
            now - lastPopulationAtMs < populationIntervalMs
        ) {
            return
        }
        lastPopulationAtMs = now
        val total = entities.size
        if (total < 0) return
        val players = entities.all().count { it.isPlayer }
        val item = Population("relay-pop-$now", now, total, players)
        try {
            ObservationExternal.offer(item)
        } catch (e: Exception) {
            // Observation is best-effort; forwarding already happened.
        }
    }

    companion object {
        const val DEFAULT_POPULATION_INTERVAL_MS = 1000L
    }
}