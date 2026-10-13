// Ported from besliky/airplay-tv (service/NetworkMonitor.kt).
// Copyright (C) 2026 besliky. Licensed under GPL-3.0-or-later.

package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Tracks local networks (Wi-Fi and Ethernet, with or without internet access) and reports
 * changes of availability or addresses, debounced so a network switch produces one update.
 * Callbacks arrive on the main thread.
 */
class NetworkMonitor(context: Context, private val onChange: (Snapshot) -> Unit) {

    data class Snapshot(val available: Boolean, val ipv4: List<String>, val hasIpv6: Boolean, val transports: String) {
        // identifies the addressing, used to decide whether services must be re-announced
        val key: String get() = "${ipv4.sorted()}|$hasIpv6|$transports"
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private val networks = mutableMapOf<Network, LinkProperties?>()
    private val transports = mutableMapOf<Network, String>()
    private var last: Snapshot? = null
    private var registered = false

    var current: Snapshot = Snapshot(false, emptyList(), false, "")
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                networks[network] = cm.getLinkProperties(network)
                scheduleUpdate()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            handler.post {
                transports[network] = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    else -> "Network"
                }
                scheduleUpdate()
            }
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            handler.post {
                networks[network] = lp
                scheduleUpdate()
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                networks.remove(network)
                transports.remove(network)
                scheduleUpdate()
            }
        }
    }

    private val update = Runnable {
        val addresses = networks.values.filterNotNull().flatMap { it.linkAddresses }.map { it.address }
        val ipv4 = addresses
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
        val hasIpv6 = addresses.any { it is Inet6Address }
        val snapshot = Snapshot(networks.isNotEmpty(), ipv4, hasIpv6, transports.values.distinct().sorted().joinToString("+"))
        current = snapshot
        if (snapshot.key != last?.key || snapshot.available != last?.available) {
            last = snapshot
            Log.i(TAG, if (snapshot.available) "network ${snapshot.transports} ${snapshot.ipv4}" else "no local network")
            onChange(snapshot)
        }
    }

    private fun scheduleUpdate() {
        handler.removeCallbacks(update)
        handler.postDelayed(update, DEBOUNCE_MS)
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot observe networks", e)
        }
        scheduleUpdate()
    }

    fun stop() {
        if (!registered) return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
        }
        registered = false
        handler.removeCallbacks(update)
        networks.clear()
        transports.clear()
        last = null
    }

    companion object {
        private const val TAG = "NetworkMonitor"
        private const val DEBOUNCE_MS = 1_200L
    }
}
