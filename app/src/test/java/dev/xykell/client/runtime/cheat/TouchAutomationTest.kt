package dev.xykell.client.runtime.cheat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TouchAutomationTest {

    @Test
    fun `cps below range clamps to one`() {
        assertEquals(1000L, ClickSchedule.nextDelayMs(0, 0, Random(1)))
        assertEquals(1000L, ClickSchedule.nextDelayMs(-5, 0, Random(1)))
    }

    @Test
    fun `cps above range clamps to twenty`() {
        assertEquals(50L, ClickSchedule.nextDelayMs(21, 0, Random(1)))
        assertEquals(50L, ClickSchedule.nextDelayMs(Int.MAX_VALUE, 0, Random(1)))
    }

    @Test
    fun `zero jitter gives exact base delay`() {
        for (cps in ClickSchedule.MIN_CPS..ClickSchedule.MAX_CPS) {
            assertEquals(
                (1000.0 / cps).toLong(),
                ClickSchedule.nextDelayMs(cps, 0, Random(7)),
            )
        }
    }

    @Test
    fun `jitter stays inside plus minus bounds`() {
        val random = Random(42)
        repeat(500) {
            val delay = ClickSchedule.nextDelayMs(1, 50, random)
            assertTrue("delay=$delay", delay in 500L..1500L)
        }
    }

    @Test
    fun `jitter above fifty clamps to fifty`() {
        val random = Random(42)
        repeat(200) {
            val delay = ClickSchedule.nextDelayMs(1, 500, random)
            assertTrue("delay=$delay", delay in 500L..1500L)
        }
    }

    @Test
    fun `negative jitter treated as zero`() {
        val random = Random(42)
        repeat(50) {
            assertEquals(1000L, ClickSchedule.nextDelayMs(1, -10, random))
        }
    }

    @Test
    fun `append caps at max steps`() {
        var steps = emptyList<MacroStep>()
        repeat(MacroStore.MAX_STEPS + 50) {
            steps = MacroStore.append(steps, MacroStep(100, 0.5, 0.5))
        }
        assertEquals(MacroStore.MAX_STEPS, steps.size)
    }

    @Test
    fun `append clamps delay and point`() {
        val out = MacroStore.append(
            emptyList(),
            MacroStep(999_999L, 5.0, -2.0),
        ).single()
        assertEquals(MacroStore.MAX_STEP_DELAY_MS, out.delayMs)
        assertEquals(1.0, out.nx, 0.0)
        assertEquals(0.0, out.ny, 0.0)
    }

    @Test
    fun `encode decode round trip`() {
        val steps = listOf(
            MacroStep(0, 0.25, 0.75),
            MacroStep(120, 0.5, 0.5),
            MacroStep(10_000, 1.0, 0.0),
        )
        assertEquals(steps, MacroStore.decode(MacroStore.encode(steps)))
    }

    @Test
    fun `decode drops corrupt entries`() {
        val raw = "1,0.5,0.5;garbage;2,x,3;3,0.1,0.1;4,1.5,NaN;not,numbers,at,all"
        val steps = MacroStore.decode(raw)
        assertEquals(2, steps.size)
        assertEquals(0.5, steps[0].nx, 0.0)
        assertEquals(0.1, steps[1].ny, 0.0)
    }

    @Test
    fun `decode empty input`() {
        assertTrue(MacroStore.decode(null).isEmpty())
        assertTrue(MacroStore.decode("").isEmpty())
        assertTrue(MacroStore.decode("   ").isEmpty())
    }

    @Test
    fun `scale maps normalized point to pixels`() {
        assertEquals(100f to 50f, MacroStore.scaleToScreen(0.5, 0.5, 200, 100))
    }

    @Test
    fun `scale clamps out of range points`() {
        assertEquals(200f to 0f, MacroStore.scaleToScreen(2.0, -1.0, 200, 100))
    }

    @Test
    fun `scale survives zero dimensions`() {
        assertEquals(1f to 1f, MacroStore.scaleToScreen(1.0, 1.0, 0, 0))
    }
}
