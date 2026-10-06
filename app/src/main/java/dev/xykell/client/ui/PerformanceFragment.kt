package dev.xykell.client.ui

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.performance.DeviceInfo
import dev.xykell.client.runtime.performance.PerformanceStore

/** App performance dashboard: real-time FPS, memory, storage,
 *  startup time, uptime. Pure app metrics — never claims to
 *  reflect Minecraft runtime performance. */
class PerformanceFragment : Fragment(R.layout.fragment_performance) {

    private var startupTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    private var lastUpdate = 0L
    private val updateIntervalMs = 500L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.perf_title).text =
            getString(R.string.perf_title)
        view.findViewById<TextView>(R.id.perf_body).text =
            getString(R.string.perf_body)
        // Choreographer callback for FPS
        // Real thread CPU time comes from the platform; java.lang.management is
        // not in the Android API surface.
        PerformanceStore.setCpuTimeSource { android.os.Debug.threadCpuTimeNanos() }
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                PerformanceStore.onFrame()
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        android.view.Choreographer.getInstance().postFrameCallback(callback)
        view.tag = callback
        // Periodic UI update
        view.postDelayed(updateRunnable, updateIntervalMs)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            val view = view ?: return
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 500) {
                lastUpdate = now
                updateUI()
            }
            view.postDelayed(this, 500)
        }
    }

    private fun updateUI() {
        val uptimeMs = SystemClock.elapsedRealtime()
        val ctx = requireContext()
        // Memory and storage come from DeviceInfo, the same reader the HUD
        // hardware-stats path uses. This screen used to re-read StatFs and
        // ActivityManager itself, which meant two sources that could disagree.
        val mem = DeviceInfo.memory(ctx)
        val availMB = mem.systemAvailableMB
        val totalMB = mem.systemTotalMB
        val storageFreeMB = mem.storageFreeMB
        val storageTotalMB = mem.storageTotalMB
        // Update views
        view?.findViewById<TextView>(R.id.perf_fps)?.text =
            String.format("%.1f FPS (%.1f ms/frame)", PerformanceStore.getFps(), PerformanceStore.getFrameTimeMs())
        view?.findViewById<TextView>(R.id.perf_memory)?.text =
            getString(R.string.perf_memory_label, PerformanceStore.getMemoryUsedMB(), PerformanceStore.getMemoryMaxMB(), availMB, totalMB)
        view?.findViewById<TextView>(R.id.perf_storage)?.text =
            getString(R.string.perf_storage_label, storageFreeMB, storageTotalMB)
        view?.findViewById<TextView>(R.id.perf_startup)?.text =
            getString(R.string.perf_startup_label, (System.currentTimeMillis() - startupTimeMs))
        view?.findViewById<TextView>(R.id.perf_uptime)?.text =
            getString(R.string.perf_uptime_label, uptimeMs / 1000)
        view?.findViewById<TextView>(R.id.perf_cpu)?.text =
            getString(R.string.perf_cpu_label, PerformanceStore.getCpuTimeMs())

        updateDetailRows(mem)
    }

    /** Long-lived facts: they change slowly, so they are read once. */
    private fun updateDetailRows(mem: DeviceInfo.Memory) {
        val v = view ?: return
        val ctx = requireContext()
        val unknown = getString(R.string.perf_unknown)
        fun s(x: Any?): String = x?.toString() ?: unknown

        v.findViewById<TextView>(R.id.perf_memory_detail).text = buildString {
            append(
                getString(
                    R.string.perf_memory_detail_fmt,
                    s(mem.javaHeapUsedMB), s(mem.javaHeapTotalMB), s(mem.javaHeapMaxMB),
                    s(mem.nativeHeapUsedMB), s(mem.nativeHeapSizeMB),
                ),
            ).append('\n')
            append(
                getString(
                    R.string.perf_system_memory_fmt,
                    s(mem.systemAvailableMB), s(mem.systemTotalMB),
                    s(mem.systemLowMemory), s(mem.systemThresholdMB),
                ),
            ).append('\n')
            append(
                getString(
                    R.string.perf_app_memory_fmt,
                    s(mem.appMemoryClassMB), s(mem.appLowMemory),
                    s(mem.storageFreeMB), s(mem.storageTotalMB),
                ),
            )
        }

        val cpu = DeviceInfo.cpu(PerformanceStore.getCpuTimeMs())
        v.findViewById<TextView>(R.id.perf_cpu_detail).text = getString(
            R.string.perf_cpu_detail_fmt,
            cpu.coreCount, s(cpu.model), s(cpu.manufacturer), s(cpu.maxFrequencyKhz),
        )

        // GL reads are cheap but must not run before a surface context exists,
        // so failures are folded into "unknown" rather than crashing.
        val gpu = DeviceInfo.gpu(ctx)
        v.findViewById<TextView>(R.id.perf_gpu_detail).text = buildString {
            append(getString(R.string.perf_gpu_section)).append('\n')
            append(getString(R.string.perf_gpu_renderer_fmt, s(gpu.renderer))).append('\n')
            append(getString(R.string.perf_gpu_vendor_fmt, s(gpu.vendor))).append('\n')
            append(
                getString(
                    R.string.perf_gpu_api_fmt,
                    s(gpu.glVersion), s(gpu.shadingLanguageVersion),
                ),
            ).append('\n')
            append(
                getString(
                    R.string.perf_gpu_limits_fmt,
                    s(gpu.maxTextureSize), s(gpu.maxTextureUnits), s(gpu.maxVertexAttribs),
                    s(gpu.vulkanSupportedByDevice),
                ),
            ).append('\n')
            // Never a percentage: no public Android API exposes GPU load.
            append(getString(R.string.perf_gpu_utilisation_fmt))
        }
    }

    override fun onDestroyView() {
        // Remove choreographer callback
        val callback = view?.tag as? android.view.Choreographer.FrameCallback
        callback?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) }
        view?.removeCallbacks(updateRunnable)
        super.onDestroyView()
    }
}