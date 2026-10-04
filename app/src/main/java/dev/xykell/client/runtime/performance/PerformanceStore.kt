package dev.xykell.client.runtime.performance

import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean

/** Pure JVM performance metrics model. Android UI layer calls these
 *  and displays results; no Android deps here. */
object PerformanceStore {

    data class Metrics(
        val fps: Double,
        val frameTimeMs: Double,
        val memoryUsedMB: Long,
        val memoryMaxMB: Long,
        val storageFreeMB: Long,
        val storageTotalMB: Long,
        val startupTimeMs: Long,
        val uptimeMs: Long
    )

    private var frameCount = 0L
    private var lastFrameTimeNanos = System.nanoTime()
    private var fpsAccumulator = 0.0
    private val threadMXBean = ManagementFactory.getThreadMXBean()

    /** Call once per rendered frame (Choreographer callback). */
    fun onFrame() {
        val now = System.nanoTime()
        val dt = (now - lastFrameTimeNanos) / 1_000_000_000.0
        lastFrameTimeNanos = now
        frameCount++
        if (dt > 0) {
            fpsAccumulator = (fpsAccumulator * 0.9) + (1.0 / dt * 0.1)
        }
    }

    fun getFps(): Double = fpsAccumulator

    fun getFrameTimeMs(): Double = if (fpsAccumulator > 0) 1000.0 / fpsAccumulator else 0.0

    fun getMemoryUsedMB(): Long = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024

    fun getMemoryMaxMB(): Long = Runtime.getRuntime().maxMemory() / 1024 / 1024

    fun getCpuTimeMs(): Long = threadMXBean.currentThreadCpuTime / 1_000_000

    fun getMetrics(uptimeMs: Long, startupTimeMs: Long): Metrics = Metrics(
        fps = getFps(),
        frameTimeMs = getFrameTimeMs(),
        memoryUsedMB = getMemoryUsedMB(),
        memoryMaxMB = getMemoryMaxMB(),
        storageFreeMB = 0L, // filled by Android layer
        storageTotalMB = 0L, // filled by Android layer
        startupTimeMs = startupTimeMs,
        uptimeMs = uptimeMs
    )
}