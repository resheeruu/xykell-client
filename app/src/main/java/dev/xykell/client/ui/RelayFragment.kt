package dev.xykell.client.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.relay.RelayService

/**
 * Relay screen: explicit upstream settings + Start/Stop for the transparent
 * RakNet pipe. Validation happens here (parse to port) and again in
 * [RelayService]; settings persist for the next Start.
 */
class RelayFragment : Fragment(R.layout.fragment_relay) {

    private var statusView: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.relay_title).text = getString(R.string.relay_title)
        view.findViewById<TextView>(R.id.relay_body).text = getString(R.string.relay_body)

        val host = view.findViewById<EditText>(R.id.relay_host)
        val upstreamPort = view.findViewById<EditText>(R.id.relay_upstream_port)
        val listenPort = view.findViewById<EditText>(R.id.relay_listen_port)
        statusView = view.findViewById(R.id.relay_status)

        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        host.setText(prefs.getString(KEY_HOST, "127.0.0.1"))
        upstreamPort.setText(prefs.getInt(KEY_UPSTREAM_PORT, 19132).toString())
        listenPort.setText(prefs.getInt(KEY_LISTEN_PORT, 19133).toString())

        view.findViewById<Button>(R.id.relay_start).setOnClickListener {
            val h = host.text.toString().trim()
            val up = upstreamPort.text.toString().toIntOrNull()
            val lp = listenPort.text.toString().toIntOrNull()
            if (h.isEmpty() || up == null || up !in 1..65535 || lp == null || lp !in 1..65535) {
                statusView?.text = getString(R.string.relay_invalid)
                return@setOnClickListener
            }
            prefs.edit()
                .putString(KEY_HOST, h)
                .putInt(KEY_UPSTREAM_PORT, up)
                .putInt(KEY_LISTEN_PORT, lp)
                .apply()
            // Apply edited settings even while running: intents queue on the
            // service in order, so Stop tears the old pipe down before Start.
            if (RelayService.running) {
                RelayService.stop(requireContext())
            }
            RelayService.start(requireContext(), h, up, lp)
            refresh()
        }
        view.findViewById<Button>(R.id.relay_stop).setOnClickListener {
            RelayService.stop(requireContext())
            refresh()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        statusView?.text = RelayService.statusText()
        refreshGuide()
    }

    /**
     * Guided setup text.
     *
     * The port shown to the player is the one the relay *actually* bound, not
     * the one requested. If 19133 is taken the service binds something else,
     * and telling the player to enter 19133 would send them to a dead port
     * with no idea why. Before the relay starts there is nothing real to
     * report, so we show the requested value and say it is not live yet.
     */
    private fun refreshGuide() {
        val mc = view ?: return

        val installed = try {
            requireContext().packageManager.getPackageInfo(GAME_PACKAGE, 0)
            requireContext().packageManager.getApplicationLabel(
                requireContext().packageManager.getApplicationInfo(GAME_PACKAGE, 0),
            ).toString() + " " + android.os.Build.VERSION.SDK_INT
        } catch (e: Exception) {
            null
        }

        mc.findViewById<TextView>(R.id.relay_mc_status)?.text =
            if (installed != null) getString(R.string.relay_mc_found, installed)
            else getString(R.string.relay_mc_missing)

        val port = if (RelayService.running && RelayService.localPort > 0) {
            RelayService.localPort
        } else {
            listenPortFallback()
        }
        mc.findViewById<TextView>(R.id.relay_addserver)?.text =
            getString(R.string.relay_addserver_fmt, port)

        mc.findViewById<TextView>(R.id.relay_ready)?.text =
            if (RelayService.online) {
                getString(R.string.relay_ready_fmt, RelayService.localPort, RelayService.target)
            } else {
                RelayService.statusText()
            }
    }

    private fun listenPortFallback(): Int =
        view?.findViewById<EditText>(R.id.relay_listen_port)?.text?.toString()
            ?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_LISTEN_PORT

    companion object {
        private const val GAME_PACKAGE = "com.mojang.minecraftpe"
        private const val DEFAULT_LISTEN_PORT = 19133
        private const val PREFS = "xykell_relay"
        private const val KEY_HOST = "host"
        private const val KEY_UPSTREAM_PORT = "upstream_port"
        private const val KEY_LISTEN_PORT = "listen_port"
    }
}
