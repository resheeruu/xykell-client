package dev.xykell.client.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.CrashGuard
import dev.xykell.client.runtime.NativeBridgeStatus
import dev.xykell.client.runtime.RuntimeStatus
import dev.xykell.client.runtime.XykellInfo
import dev.xykell.client.runtime.observation.ObservationService
import dev.xykell.client.runtime.network.RelayTuning
import dev.xykell.client.runtime.observation.Observations
import dev.xykell.client.runtime.relay.CodecDiagnostics
import dev.xykell.client.runtime.relay.DecodeLimits
import dev.xykell.client.runtime.relay.RelayService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics screen: crash reports (view/export/delete), observation
 * counters, native bridge health, and version info — everything the
 * completion pass requires to be inspectable from inside the app.
 *
 * Counts only for observation state: no message text, names, or
 * positions ever reach this screen. Crash reports were already
 * redacted by CrashGuard when written.
 */
class DiagnosticsFragment : Fragment(R.layout.fragment_diagnostics) {

    private var statusView: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.diag_title).text = getString(R.string.diag_title)
        view.findViewById<TextView>(R.id.diag_body).text = getString(R.string.nav_diagnostics_desc)
        view.findViewById<TextView>(R.id.diag_version_header).text =
            getString(R.string.diag_version_header)
        view.findViewById<TextView>(R.id.diag_bridge_header).text =
            getString(R.string.diag_bridge_header)
        view.findViewById<TextView>(R.id.diag_observation_header).text =
            getString(R.string.diag_observation_header)
        view.findViewById<TextView>(R.id.diag_crash_header).text =
            getString(R.string.diag_crash_header)
        view.findViewById<TextView>(R.id.diag_limits_header).text =
            getString(R.string.diag_limits_header)
        statusView = view.findViewById(R.id.diag_crash_status)

        view.findViewById<Button>(R.id.diag_crash_export).setOnClickListener {
            exportReports()
        }
        view.findViewById<Button>(R.id.diag_crash_delete_all).setOnClickListener {
            CrashGuard.clearCrashReports(requireContext())
            render(requireView())
        }

        render(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { render(it) }
    }

    override fun onDestroyView() {
        statusView = null
        super.onDestroyView()
    }

    private fun render(view: View) {
        view.findViewById<TextView>(R.id.diag_version).text = """
            App ${XykellInfo.XYKELL_VERSION} / native ${XykellInfo.NATIVE_VERSION}
            Levi target ${XykellInfo.LEVI_TARGET}, preloader ${XykellInfo.PRELOADER_PIN}
            Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.joinToString(",")})
        """.trimIndent()

        view.findViewById<TextView>(R.id.diag_bridges).text = buildString {
            append(NativeBridgeStatus.summary())
            append('\n')
            append(RuntimeStatus.summary())
        }

        val nativeStats = Observations.stats()
        view.findViewById<TextView>(R.id.diag_observation).text = buildString {
            append(ObservationService.statusText())
            append('\n')
            append(
                if (nativeStats.isEmpty()) getString(R.string.diag_observation_native_down)
                else getString(R.string.diag_observation_native, nativeStats),
            )
        }

        renderCrashList(view)
        renderLimits(view)
    }

    /**
     * The relay's real, current numbers rather than the constants they were
     * configured from.
     *
     * Three things are deliberately not smoothed over. The protocol line says
     * "device only" when only one leg has reported, because this build
     * generates its packet table from a single vendored schema and cannot
     * honestly claim a support matrix — see [CodecDiagnostics]. The socket line
     * separates what was requested from what the kernel granted, because those
     * differing is the usual explanation for dropped datagrams. And when no
     * session has opened the sockets, the granted size reads "unknown" instead
     * of a zero that would look like a real measurement.
     */
    private fun renderLimits(view: View) {
        val protocol = RelayService.negotiatedProtocol
        val buffers = RelayService.socketBuffers
        view.findViewById<TextView>(R.id.diag_limits).text = buildString {
            append(CodecDiagnostics.summary(protocol, protocol))
            append('\n')
            if (buffers == null) {
                append(
                    "mtu ${RelayTuning.MTU_BYTES}B, requested " +
                        "${RelayTuning.bufferRequest() / 1024}KiB, granted unknown"
                )
            } else {
                append(buffers)
            }
            append('\n')
            append(DecodeLimits.DEFAULT.describe())
        }
    }

    private fun renderCrashList(view: View) {
        val reports = CrashGuard.listReports(requireContext())
        view.findViewById<TextView>(R.id.diag_crash_count).text =
            getString(R.string.diag_crash_count, reports.size)
        val list = view.findViewById<LinearLayout>(R.id.diag_crash_list)
        list.removeAllViews()
        if (reports.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = getString(R.string.diag_crash_empty)
                textSize = 13f
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        requireContext(), R.color.xykell_muted,
                    ),
                )
            }
            list.addView(empty)
            return
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val density = requireContext().resources.displayMetrics.density
        for (report in reports) {
            list.addView(reportRow(report, dateFormat, density))
        }
    }

    private fun reportRow(
        report: CrashGuard.ReportInfo,
        dateFormat: SimpleDateFormat,
        density: Float,
    ): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val labels = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        labels.addView(TextView(requireContext()).apply {
            text = report.name
            textSize = 13f
        })
        labels.addView(TextView(requireContext()).apply {
            text = getString(
                R.string.diag_crash_meta,
                report.sizeBytes,
                dateFormat.format(Date(report.modifiedAtMs)),
            )
            textSize = 12f
            setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.xykell_muted,
                ),
            )
        })
        row.addView(labels)

        row.addView(Button(requireContext()).apply {
            text = getString(R.string.diag_crash_view)
            minHeight = (48 * density).toInt()
            minimumHeight = (48 * density).toInt()
            setOnClickListener { viewReport(report.name) }
        })
        row.addView(Button(requireContext()).apply {
            text = getString(R.string.diag_crash_delete)
            minHeight = (48 * density).toInt()
            minimumHeight = (48 * density).toInt()
            setOnClickListener {
                if (CrashGuard.deleteReport(requireContext(), report.name)) {
                    statusView?.text = getString(R.string.diag_crash_deleted, report.name)
                }
                view?.let { render(it) }
            }
        })
        return row
    }

    private fun viewReport(name: String) {
        val content = CrashGuard.readReport(requireContext(), name)
        if (content == null) {
            statusView?.text = getString(R.string.diag_crash_view_failed)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.diag_crash_view_title, name))
            .setMessage(content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun exportReports() {
        val reports = CrashGuard.listReports(requireContext())
        val dir = File(requireContext().getExternalFilesDir(null), "Xykell/crash")
        if (!dir.isDirectory && !dir.mkdirs()) {
            statusView?.text = getString(R.string.diag_crash_export_failed_dir)
            return
        }
        var copied = 0
        for (report in reports) {
            val src = CrashGuard.readReport(requireContext(), report.name) ?: continue
            try {
                File(dir, report.name).writeText(src)
                copied++
            } catch (_: Exception) {
                // Continue with the rest; the count reports what landed.
            }
        }
        statusView?.text = getString(R.string.diag_crash_exported, copied)
    }
}
