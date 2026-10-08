package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.cheat.MacroStep

/**
 * Replays [ModuleRuntime] tap plans through whichever surface executes taps.
 *
 * The plans themselves are pure (see [TapPlan]); this is the only piece that has
 * to leave the JVM — it turns "tap these points this tick" into actual gestures
 * on the same surface a finger uses. Nothing here forges a packet: the game
 * still sends every action, it just gets asked to send it sooner.
 *
 * Deliberately takes a plain function rather than the accessibility service, for
 * two reasons: this class then has no Android dependency at all, and the caller
 * decides what "play" means — the relay wires it to `TouchAutomationService`,
 * and a test wires it to a list.
 *
 * One timer, not one per module: a 4-tick cadence and a 20-tick cadence both
 * need a tick, and two timers would race on the accessibility service's single
 * in-flight gesture.
 *
 * The executor is optional. It is off until the user enables it in system
 * settings and it can die at any time, so a null executor means plans are
 * computed and dropped — never a crash, never an unbounded queue.
 */
class ModuleTapRunner(
    private val runtime: ModuleRuntime,
    private val play: (List<MacroStep>) -> Unit,
    private val intervalMs: Long = TICK_MS,
    private val postDelayed: (Runnable, Long) -> Unit,
) {

    private val tick = object : Runnable {
        override fun run() {
            // Stop rather than reschedule: a stopped runner must not come back
            // from an already-queued callback.
            if (!running) return
            runOnce()
            postDelayed(this, intervalMs)
        }
    }

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        postDelayed(tick, intervalMs)
    }

    fun stop() {
        running = false
    }

    /**
     * One tick's worth of plans, handed to the executor.
     *
     * Returns the steps it produced so a caller can see what was due without
     * needing the executor at all.
     */
    fun runOnce(): List<MacroStep> {
        val steps = try {
            runtime.onTick()
        } catch (e: Exception) {
            // A plan that throws must not stop the relay or the next tick.
            return emptyList()
        }
        if (steps.isEmpty()) return steps
        try {
            play(steps)
        } catch (e: Exception) {
            // The executor refused (service disabled mid-session): drop this
            // tick's steps rather than queue them for a surface that is gone.
        }
        return steps
    }

    companion object {
        /** 20 Hz: 50 ms, twice a game tick, cheap and responsive enough. */
        const val TICK_MS = 50L
    }
}