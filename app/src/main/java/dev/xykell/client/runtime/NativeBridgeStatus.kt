package dev.xykell.client.runtime

/**
 * Makes a native-binding failure observable instead of invisible.
 *
 * Every `Native*` bridge wraps its call in a guard that turns an
 * UnsatisfiedLinkError into null / false / "". That is the right behaviour for
 * a caller, and the wrong behaviour for a user: a missing symbol made every
 * native-backed screen render as if it had no data, with nothing to look at.
 *
 * Each guard records the failure here first. Anything that renders native state
 * can then show "native binding unavailable" rather than an empty list, and
 * diagnostics can report which bridge is broken instead of guessing.
 *
 * Deliberately not a logger: no message is emitted, nothing is written to disk,
 * and no symbol or path text is retained. Only the bridge name and whether the
 * call succeeded.
 */
object NativeBridgeStatus {

    private val failures = LinkedHashMap<String, String>()

    /** Call from a guard's catch block. [symbol] is for humans, never evaluated. */
    @Synchronized
    fun recordFailure(bridge: String, symbol: String) {
        // Bounded: one entry per (bridge, symbol) pair, capped overall.
        if (failures.size >= MAX_ENTRIES) {
            failures.entries.iterator().let { it.next(); it.remove() }
        }
        failures["$bridge.$symbol"] = "UnsatisfiedLinkError"
    }

    @Synchronized
    fun recordSuccess(bridge: String, symbol: String) {
        failures.remove("$bridge.$symbol")
    }

    /** True when this bridge has never failed, i.e. it is safe to trust. */
    @Synchronized
    fun healthy(bridge: String): Boolean =
        failures.keys.none { it.startsWith("$bridge.") }

    /** Bridges that have at least one failing symbol. */
    @Synchronized
    fun failingBridges(): List<String> = failures.keys
        .map { it.substringBefore('.') }
        .distinct()
        .sorted()

    @Synchronized
    fun failureCount(): Int = failures.size

    /**
     * One-line summary for a status surface. Reports the count and the bridges,
     * never the exception text, so nothing internal leaks into the UI.
     */
    @Synchronized
    fun summary(): String = if (failures.isEmpty()) {
        "native bridges: OK"
    } else {
        "native bridges: ${failures.size} unresolved (${failingBridges().joinToString(", ")})"
    }

    @Synchronized
    fun clear() = failures.clear()

    private const val MAX_ENTRIES = 128
}