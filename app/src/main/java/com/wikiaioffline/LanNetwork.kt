package com.wikiaioffline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

internal object LanNetwork {
    data class Target(val network: Network, val address: Inet4Address)

    // Include local networks without a validated internet connection.
    @Suppress("DEPRECATION")
    fun targets(context: Context): List<Target> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val active = manager.activeNetwork
        return manager.allNetworks.sortedBy { it != active }.flatMap { network ->
            val capabilities = manager.getNetworkCapabilities(network)
                ?: return@flatMap emptyList()
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                return@flatMap emptyList()
            }
            manager.getLinkProperties(network)?.linkAddresses.orEmpty()
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .distinct()
                .map { address -> Target(network, address) }
        }
    }
}
