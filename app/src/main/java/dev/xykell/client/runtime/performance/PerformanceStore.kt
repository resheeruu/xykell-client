package dev.xykell.client.runtime.performance

/** Performance metrics model. Deliberately free of Android imports so it stays
 *  unit-testable on the host JVM; the Android layer supplies anything that
 *  needs a platform API.
 *
 *  Note: thread CPU time comes from an injected source, not
 *  java.lang.management. That package is not part of the Android API surface
 *  (AGP strips it from the compile classpath), so referencing ManagementFactory
 *  compiles against a desktop JDK but fails in the real Gradle build. Android
 *  callers wire android.os.Debug.threadCpuTimeNanos(). */
object PerformanceStore {

    data class Metrics(
        val fps: Double,
        val frameTimeMs: Double,
        val memoryUsedMB: Long,
        val memoryMaxMB: Long,
        val storageFreeMB: Long,
        val storageTotalMB: Long,
        val startupTimeMs: Long,
        val uptimeMs: Long,
    )

    private var frameCount = 0L
    private var lastFrameTimeNanos = System.nanoTime()
    private var fpsAccumulator = 0.0

    @Volatile
    private var cpuTimeNanos: (() -> Long)? = null

    /** Android: PerformanceStore.setCpuTimeSource { Debug.threadCpuTimeNanos() }. */
    fun setCpuTimeSource(source: () -> Long) {
        cpuTimeNanos = source
    }

    /** Drops the source; [getCpuTimeMs] goes back to reporting unknown. */
    fun clearCpuTimeSource() {
        cpuTimeNanos = null
    }

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

    fun getMemoryUsedMB(): Long =
        (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024

    fun getMemoryMaxMB(): Long = Runtime.getRuntime().maxMemory() / 1024 / 1024

    /**
     * -1 when unknown: either no source is wired, or the source reported a
     * negative value. The sentinel is checked before the division because
     * -1 / 1_000_000 is 0 in integer arithmetic, which would read as a real
     * measurement.
     */
    fun getCpuTimeMs(): Long {
        val nanos = cpuTimeNanos?.invoke() ?: return -1L
        if (nanos < 0) return -1L
        return nanos / 1_000_000
    }

    fun getMetrics(uptimeMs: Long, startupTimeMs: Long): Metrics = Metrics(
        fps = getFps(),
        frameTimeMs = getFrameTimeMs(),
        memoryUsedMB = getMemoryUsedMB(),
        memoryMaxMB = getMemoryMaxMB(),
        storageFreeMB = 0L, // filled by Android layer
        storageTotalMB = 0L, // filled by Android layer
        startupTimeMs = startupTimeMs,
        uptimeMs = uptimeMs,
    )
}
