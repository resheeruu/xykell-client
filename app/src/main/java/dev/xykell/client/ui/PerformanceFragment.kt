package dev.xykell.client.ui

import android.app.ActivityManager
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
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
        val metrics = PerformanceStore.getMetrics(uptimeMs, System.currentTimeMillis() - startupTimeMs)
        // Storage
        val statFs = StatFs(Environment.getDataDirectory().absolutePath)
        val blockSize = statFs.blockSizeLong
        val freeBlocks = statFs.availableBlocksLong
        val totalBlocks = statFs.blockCountLong
        val storageFreeMB = (freeBlocks * statFs.blockSizeLong) / 1024 / 1024
        val storageTotalMB = (totalBlocks * blockSize) / 1024 / 1024
        // Memory
        val memInfo = ActivityManager.MemoryInfo()
        requireActivity().getSystemService(ActivityManager::class.java).getMemoryInfo(memInfo)
        val availMB = memInfo.availMem / 1024 / 1024
        val totalMB = memInfo.totalMem / 1024 / 1024
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
    }

    override fun onDestroyView() {
        // Remove choreographer callback
        val callback = view?.tag as? android.view.Choreographer.FrameCallback
        callback?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) }
        view?.removeCallbacks(updateRunnable)
        super.onDestroyView()
    }
}