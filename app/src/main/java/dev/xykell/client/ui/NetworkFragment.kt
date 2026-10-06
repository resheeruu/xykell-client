package dev.xykell.client.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.network.NetworkDiagnostics
import dev.xykell.client.runtime.network.NetworkProbe

/**
 * Connectivity facts and an opt-in reachability probe.
 *
 * Four features share this screen because they read the same two APIs:
 * connection status and diagnostics come from [NetworkDiagnostics.link],
 * while ping and the latency graph come from a user-started [NetworkProbe].
 *
 * Honesty constraint: a TCP connect round trip is not Bedrock latency. The
 * screen says so in the UI and never labels a socket probe as game latency,
 * so these stay PARTIAL until a real session can validate them.
 */
class NetworkFragment : Fragment(R.layout.fragment_network) {

    private var prober: NetworkProbe.Prober? = null
    private var statusView: TextView? = null
    private var resultView: TextView? = null
    private var graphView: TextView? = null
    private var statsView: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prober = NetworkProbe.Prober()

        view.findViewById<TextView>(R.id.network_title).text = getString(R.string.network_title)
        view.findViewById<TextView>(R.id.network_body).text = getString(R.string.network_body)
        view.findViewById<TextView>(R.id.network_tcp_notice).text =
            getString(R.string.network_tcp_notice)

        statusView = view.findViewById(R.id.network_status)
        resultView = view.findViewById(R.id.network_probe_result)
        graphView = view.findViewById(R.id.network_graph)
        statsView = view.findViewById(R.id.network_stats)

        view.findViewById<Button>(R.id.network_refresh).setOnClickListener { renderStatus() }
        view.findViewById<Button>(R.id.network_history_clear).setOnClickListener {
            prober?.history()?.clear()
            renderHistory()
        }

        val host = view.findViewById<EditText>(R.id.network_host)
        val port = view.findViewById<EditText>(R.id.network_port)
        view.findViewById<Button>(R.id.network_probe).setOnClickListener {
            startProbe(host, port)
        }

        renderStatus()
        renderHistory()
    }

    private fun startProbe(host: EditText, port: EditText) {
        val p = prober ?: return
        val portValue = port.text.toString().toIntOrNull()
        if (portValue == null) {
            resultView?.text = getString(R.string.network_probe_result_fail,
                host.text.toString(), getString(R.string.network_invalid_port))
            return
        }
        resultView?.text = getString(R.string.network_probe_running)
        p.probe(host.text.toString().trim(), portValue) { probe ->
            val text = if (probe.ok) {
                getString(
                    R.string.network_probe_result_ok,
                    probe.host + ":" + probe.port,
                    probe.latencyMs!!,
                )
            } else {
                getString(
                    R.string.network_probe_result_fail,
                    probe.host + ":" + probe.port,
                    probe.error ?: getString(R.string.network_unknown),
                )
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                resultView?.text = text
                renderHistory()
            }
        }
    }

    private fun renderStatus() {
        val link = NetworkDiagnostics.link(requireContext().applicationContext)
        val lines = buildString {
            appendLine(getString(R.string.network_label_status) + ": " + when {
                link.connected == null -> getString(R.string.network_unknown)
                link.connected -> getString(R.string.network_connected)
                else -> getString(R.string.network_disconnected)
            })
            appendLine(getString(R.string.network_label_transport) + ": " +
                (link.transport ?: getString(R.string.network_unknown)))
            appendLine(getString(R.string.network_label_metered) + ": " + boolText(link.metered))
            appendLine(getString(R.string.network_label_validated) + ": " + boolText(link.validated))
            appendLine(getString(R.string.network_label_interface) + ": " +
                (link.interfaceName ?: getString(R.string.network_unknown)))
            if (link.reason != null) {
                append(getString(R.string.network_label_reason) + ": " + link.reason)
            }
        }
        statusView?.text = lines.trimEnd()
    }

    private fun boolText(value: Boolean?): String = when (value) {
        null -> getString(R.string.network_unknown)
        true -> getString(R.string.network_yes)
        false -> getString(R.string.network_no)
    }

    private fun renderHistory() {
        val history = prober?.history() ?: return
        val samples = history.samples()
        val graph = graphView ?: return
        if (samples.isEmpty()) {
            graph.text = getString(R.string.network_graph_empty)
            statsView?.text = ""
            return
        }
        val max = (samples.maxOrNull() ?: 0L).coerceAtLeast(1L)
        graph.text = samples.takeLast(GRAPH_POINTS).joinToString("") { ms ->
            val level = ((ms.toDouble() / max) * GRAPH_SCALE).toInt().coerceIn(0, GRAPH_SCALE)
            BARS[level].toString()
        }
        statsView?.text = getString(
            R.string.network_graph_stats,
            history.latest() ?: 0L,
            history.average() ?: 0L,
            history.min() ?: 0L,
            history.max() ?: 0L,
            samples.size,
        )
    }

    override fun onDestroyView() {
        prober?.close()
        prober = null
        statusView = null
        resultView = null
        graphView = null
        statsView = null
        super.onDestroyView()
    }

    private companion object {
        const val GRAPH_POINTS = 48
        const val GRAPH_SCALE = 8
        const val BARS = "▁▂▃▄▅▆▇█"
    }
}
