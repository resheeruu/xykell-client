package dev.xykell.client.runtime.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceStoreTest {

    @Test
    fun fpsAccumulatorSmoke() {
        // Just verify no crash on calls
        PerformanceStore.onFrame()
        val fps = PerformanceStore.getFps()
        val frameTime = PerformanceStore.getFrameTimeMs()
        assertTrue(fps >= 0.0)
        assertTrue(frameTime >= 0.0)
    }

    @Test
    fun memoryMetricsNonNegative() {
        val used = PerformanceStore.getMemoryUsedMB()
        val max = PerformanceStore.getMemoryMaxMB()
        assertTrue(used >= 0)
        assertTrue(max >= used)
    }

    @Test
    fun cpuTimeNonNegative() {
        assertTrue(PerformanceStore.getCpuTimeMs() >= 0)
    }

    @Test
    fun metricsAggregation() {
        val m = PerformanceStore.getMetrics(1000L, 500L)
        assertEquals(1000L, m.uptimeMs)
        assertEquals(500L, m.startupTimeMs)
    }
}