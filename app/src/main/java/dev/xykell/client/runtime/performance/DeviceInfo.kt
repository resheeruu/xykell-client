package dev.xykell.client.runtime.performance

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES20
import android.os.Build
import android.os.Debug
import android.os.StatFs

/**
 * Device capability and resource facts read from public Android APIs.
 *
 * Deliberately separates *information* from *utilisation*:
 *   - Memory: heap, native heap, system available/total, low-memory and trim
 *     state, app memory class, storage. All measured, none guessed.
 *   - CPU: the app process's own CPU time, core count, ABI, device strings.
 *     No fabricated load percentage.
 *   - GPU: real driver strings and GL/ES + Vulkan capability limits. GPU
 *     *utilisation* is not exposed by any public Android API, so it is
 *     reported as unavailable rather than estimated.
 *
 * Any value the platform or the vendor hides is `null` and rendered as
 * unknown. There is no code path that produces a number it did not read.
 */
object DeviceInfo {

    /**
     * No public Android API exposes GPU utilisation (no counter, no
     * standardised timing query). Callers present capability data and say
     * utilisation is unavailable, rather than deriving a plausible percentage
     * from anything. Kept as a constant so no caller can quietly invent one.
     */
    const val GPU_UTILISATION_AVAILABLE = false

    data class Memory(
        val javaHeapUsedMB: Long?,
        val javaHeapTotalMB: Long?,
        val javaHeapMaxMB: Long?,
        val nativeHeapUsedMB: Long?,
        val nativeHeapSizeMB: Long?,
        val systemAvailableMB: Long?,
        val systemTotalMB: Long?,
        val systemLowMemory: Boolean?,
        val systemThresholdMB: Long?,
        val appMemoryClassMB: Int?,
        val appLowMemory: Boolean?,
        val storageTotalMB: Long?,
        val storageFreeMB: Long?,
    ) {
        val anyKnown: Boolean
            get() = javaHeapUsedMB != null || nativeHeapUsedMB != null ||
                systemAvailableMB != null
    }

    data class Cpu(
        val processCpuTimeMs: Long?,
        val coreCount: Int,
        val abi: String?,
        val model: String?,
        val manufacturer: String?,
        val maxFrequencyKhz: Int?,
    ) {
        val anyKnown: Boolean get() = processCpuTimeMs != null || model != null
    }

    data class Gpu(
        val renderer: String?,
        val vendor: String?,
        val glVersion: String?,
        val shadingLanguageVersion: String?,
        val vulkanSupportedByDevice: Boolean?,
        val vulkanVersion: String?,
        val maxTextureSize: Int?,
        val maxTextureUnits: Int?,
        val maxVertexAttribs: Int?,
        val maxViewportWidth: Int?,
        val maxViewportHeight: Int?,
    ) {
        val anyKnown: Boolean get() = renderer != null || glVersion != null
    }

    fun memory(context: Context): Memory {
        val rt = Runtime.getRuntime()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val sys = am?.let { runCatching { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }.getOrNull() }
        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        return Memory(
            javaHeapUsedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
            javaHeapTotalMB = rt.totalMemory() / 1024 / 1024,
            javaHeapMaxMB = rt.maxMemory() / 1024 / 1024,
            nativeHeapUsedMB = mb { Debug.getNativeHeapAllocatedSize() },
            nativeHeapSizeMB = mb { Debug.getNativeHeapSize() },
            systemAvailableMB = sys?.availMem?.let { it / 1024 / 1024 },
            systemTotalMB = sys?.totalMem?.let { it / 1024 / 1024 },
            systemLowMemory = sys?.lowMemory,
            systemThresholdMB = sys?.threshold?.let { it / 1024 / 1024 },
            appMemoryClassMB = memoryClass(am),
            appLowMemory = runCatching { am?.isLowRamDevice }.getOrNull(),
            storageTotalMB = stat?.let { it.totalBytes / 1024 / 1024 },
            storageFreeMB = stat?.let { it.availableBytes / 1024 / 1024 },
        )
    }

    /**
     * @param processCpuTimeMs from the injected PerformanceStore source, or
     *   null when unknown. This class never reads thread CPU time itself,
     *   because java.lang.management is not in the Android API surface.
     */
    fun cpu(processCpuTimeMs: Long?): Cpu = Cpu(
        processCpuTimeMs = processCpuTimeMs,
        coreCount = Runtime.getRuntime().availableProcessors(),
        abi = Build.SUPPORTED_ABIS?.firstOrNull()?.takeIf { it.isNotBlank() },
        model = buildField { Build.MODEL },
        manufacturer = buildField { Build.MANUFACTURER },
        maxFrequencyKhz = runCatching {
            java.io.File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
                .takeIf { it.canRead() }?.readText()?.trim()?.toInt()
        }.getOrNull(),
    )

    /**
     * Real driver strings and limits. Call off the main thread: it touches GL.
     * Values the driver does not expose come back null.
     */
    fun gpu(context: Context): Gpu = Gpu(
        renderer = glString { GLES20.GL_RENDERER },
        vendor = glString { GLES20.GL_VENDOR },
        glVersion = glString { GLES20.GL_VERSION },
        shadingLanguageVersion = glString { GLES20.GL_SHADING_LANGUAGE_VERSION },
        vulkanSupportedByDevice = runCatching {
            context.packageManager.hasSystemFeature("android.hardware.vulkan.level")
        }.getOrNull(),
        // The device supports Vulkan but its API version needs a VkInstance.
        // Xykell does not create one, so this stays unknown rather than guessed.
        vulkanVersion = null,
        maxTextureSize = glInt { GLES20.GL_MAX_TEXTURE_SIZE },
        maxTextureUnits = glInt { GLES20.GL_MAX_TEXTURE_IMAGE_UNITS },
        maxVertexAttribs = glInt { GLES20.GL_MAX_VERTEX_ATTRIBS },
        maxViewportWidth = glInt { GLES20.GL_MAX_VIEWPORT_DIMS },
        maxViewportHeight = null,
    )

    // --- defensive helpers

    private inline fun mb(get: () -> Long): Long? = runCatching { get() / 1024 / 1024 }.getOrNull()

    private fun buildField(get: () -> String?): String? = runCatching {
        get()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun memoryClass(am: ActivityManager?): Int? = runCatching {
        am?.let { ActivityManager::class.java.getMethod("getMemoryClass").invoke(it) as? Int }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private inline fun glString(name: () -> Int): String? = runCatching {
        GLES20.glGetString(name())?.takeIf { it.isNotBlank() }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private inline fun glInt(name: () -> Int): Int? = runCatching {
        val v = IntArray(1)
        GLES20.glGetIntegerv(name(), v, 0)
        v[0].takeIf { it > 0 }
    }.getOrNull()
}
