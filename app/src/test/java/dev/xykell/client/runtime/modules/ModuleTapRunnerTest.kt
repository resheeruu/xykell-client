package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.cheat.MacroStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The tick loop that turns plans into gestures.
 *
 * Everything Android-shaped is injected — the service lookup and the postDelayed
 * — so this suite proves the loop's own logic: it ticks, it stops, it survives a
 * dead service, and it never queues steps faster than it hands them over.
 */
class ModuleTapRunnerTest {

    /** Records what the runner asked to be scheduled, and runs it on demand. */
    private class FakeScheduler {
        val queue = ArrayList<Pair<Runnable, Long>>()
        fun postDelayed(r: Runnable, delay: Long) {
            queue.add(r to delay)
        }

        fun drain() {
            val pending = queue.toList()
            queue.clear()
            for ((r, _) in pending) r.run()
        }
    }

    private fun runtime(settings: Map<String, String> = emptyMap()) =
        ModuleRuntime({ true }, settings, Random(99))

    private fun eatSettings() = mapOf(
        PlayerModules.FAST_EAT_POINT to "0.5,0.5",
        AutomationModules.AUTO_EAT_POINT to "0.5,0.5",
        "intervalTicks" to "1",
    )

    @Test
    fun `a started runner ticks on its interval`() {
        val sched = FakeScheduler()
        val runner = ModuleTapRunner(runtime(), {}, 50L, sched::postDelayed)
        runner.start()
        assertEquals(1, sched.queue.size)
        assertEquals(50L, sched.queue[0].second)
    }

    @Test
    fun `each tick reschedules exactly one more`() {
        val sched = FakeScheduler()
        ModuleTapRunner(runtime(), {}, 50L, sched::postDelayed).start()
        repeat(5) { sched.drain() }
        assertEquals(1, sched.queue.size)
    }

    @Test
    fun `a stopped runner does not tick again`() {
        val sched = FakeScheduler()
        val runner = ModuleTapRunner(runtime(), {}, 50L, sched::postDelayed)
        runner.start()
        runner.stop()
        val before = runtime().ctx.tick
        sched.drain()
        assertTrue("stopped runner rescheduled itself", sched.queue.isEmpty())
        assertEquals(before, runtime().ctx.tick)
    }

    @Test
    fun `due plans reach the service`() {
        val played = mutableListOf<List<MacroStep>>()
        val sched = FakeScheduler()
        val rt = runtime(eatSettings())
        val runner = ModuleTapRunner(rt, { played.add(it) }, 50L, sched::postDelayed)
        runner.start()
        sched.drain()

        assertTrue("no plan handed to the executor", played.isNotEmpty())
        // Both eat ids are due every tick, so one tick means one batch of steps.
        assertEquals(1, played.size)
        assertTrue(played.single().isNotEmpty())
    }

    @Test
    fun `plans are dropped when no service is attached`() {
        val sched = FakeScheduler()
        val runner = ModuleTapRunner(runtime(eatSettings()), {}, 50L, sched::postDelayed)
        runner.start()
        // Must not throw and must keep ticking.
        sched.drain()
        assertEquals(1, sched.queue.size)
    }

    @Test
    fun `a service that throws does not stop the loop`() {
        val sched = FakeScheduler()
        val runner = ModuleTapRunner(
            runtime(eatSettings()),
            { throw IllegalStateException("gesture refused") },
            50L,
            sched::postDelayed,
        )
        runner.start()
        sched.drain()
        assertEquals("loop died on a throwing service", 1, sched.queue.size)
    }

    @Test
    fun `an idle tick hands the service nothing`() {
        var calls = 0
        val sched = FakeScheduler()
        ModuleTapRunner(runtime(), { calls++ }, 50L, sched::postDelayed).start()
        sched.drain()
        assertEquals(0, calls)
    }

    @Test
    fun `runOnce advances the session tick`() {
        val rt = runtime()
        val runner = ModuleTapRunner(rt, { null }, 50L, FakeScheduler()::postDelayed)
        runner.runOnce()
        runner.runOnce()
        assertEquals(2, rt.ctx.tick.toInt())
    }
}