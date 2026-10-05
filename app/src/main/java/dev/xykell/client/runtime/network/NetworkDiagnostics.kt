package dev.xykell.client.runtime.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI

/**
 * Connectivity status from public Android APIs. Split from [NetworkProbe] so
 * the probe and history stay free of Android imports and testable on the host.
 *
 * Any value the platform withholds is null and rendered as unknown.
 */
object NetworkDiagnostics {

    data class Link(
        val connected: Boolean?,
        val transport: String?,
        val metered: Boolean?,
        val validated: Boolean?,
        val interfaceName: String?,
        val available: Boolean,
        val reason: String?,
    ) {
        val anyKnown: Boolean get() = transport != null || connected != null
    }

    fun link(context: Context): Link {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Link(
                null, null, null, null, null, available = false,
                reason = "ConnectivityManager unavailable",
            )
        val network: Network? = cm.activeNetwork
        if (network == null) {
            return Link(
                connected = false, transport = null, metered = null, validated = null,
                interfaceName = null, available = true, reason = "no active network",
            )
        }
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        if (caps == null) {
            return Link(
                connected = null, transport = null, metered = null, validated = null,
                interfaceName = null, available = true,
                reason = "capabilities unavailable",
            )
        }
        return Link(
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            transport = transportName(caps),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            interfaceName = network.toString(),
            available = true,
            reason = null,
        )
    }

    private fun transportName(caps: NetworkCapabilities): String? = when {
        caps.hasTransport(TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(TRANSPORT_VPN) -> "vpn"
        else -> null
    }
}
