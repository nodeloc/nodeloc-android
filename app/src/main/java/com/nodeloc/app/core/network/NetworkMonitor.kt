package com.nodeloc.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the device has a validated connection, as the system sees it.
 *
 * Only for one thing: a screen showing a snapshot because the network was gone
 * (issue #1) refetches the moment it comes back, rather than leaving the reader
 * on an "离线" copy until they think to pull. Never used to decide whether to
 * *try* a request — the request itself is the only honest test of that.
 */
class NetworkMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        }
    }

    private fun isOnlineNow(): Boolean = runCatching {
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)
}
