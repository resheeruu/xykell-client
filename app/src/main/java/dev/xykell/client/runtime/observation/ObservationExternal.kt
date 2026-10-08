package dev.xykell.client.runtime.observation

/**
 * Static sink bridge: lets a process-local producer outside the
 * ObservationService package (the relay tap translator, same process)
 * hand Translated items to the one active observation session. Design
 * constraint: no RIBinder surface, no exported entry, no IPC — attach
 * happens only from ObservationService itself, so an external app can
 * never reach this (privacy: no remote observation surface).
 *
 * offer() returns false while no sink is attached — that IS the
 * observation-off gate (natural gating, no extra flag).
 */
object ObservationExternal {

    @Volatile
    private var sink: ((Translated) -> Unit)? = null

    fun attach(sinkFn: (Translated) -> Unit) {
        sink = sinkFn
    }

    fun detach() {
        sink = null
    }

    fun offer(item: Translated): Boolean {
        val s = sink ?: return false
        s(item)
        return true
    }
}
