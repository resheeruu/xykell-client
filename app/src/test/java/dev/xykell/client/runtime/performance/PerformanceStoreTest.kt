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
    fun cpuTimeIsUnknownUntilASourceIsWired() {
        // No java.lang.management: AGP strips it from the Android classpath, so
        // an unwired store must report "unknown" (-1), never a fake zero.
        PerformanceStore.clearCpuTimeSource()
        assertEquals(-1L, PerformanceStore.getCpuTimeMs())
    }

    @Test
    fun cpuTimeUsesTheInjectedSource() {
        PerformanceStore.setCpuTimeSource { 7_500_000L }
        assertEquals(7L, PerformanceStore.getCpuTimeMs())
        // A negative reading stays -1: -1 / 1_000_000 is 0, which would read as
        // a real measurement.
        PerformanceStore.setCpuTimeSource { -1L }
        assertEquals(-1L, PerformanceStore.getCpuTimeMs())
        PerformanceStore.clearCpuTimeSource()
    }

    @Test
    fun metricsAggregation() {
        val m = PerformanceStore.getMetrics(1000L, 500L)
        assertEquals(1000L, m.uptimeMs)
        assertEquals(500L, m.startupTimeMs)
    }
}