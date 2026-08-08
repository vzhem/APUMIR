package com.vladimir.messenger.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.vladimir.messenger.data.RustBridge

/**
 * NetworkMonitor — слушает изменения сети и уведомляет Rust-ядро.
 */
class NetworkMonitor(private val context: Context) {

    private val TAG = "NetworkMonitor"

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available: $network")
            RustBridge.onNetworkAvailable()
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "Network lost: $network")
            RustBridge.onNetworkLost()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities
        ) {
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            Log.d(TAG, "Network caps changed: internet=$hasInternet wifi=$isWifi mobile=$isMobile")
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            Log.i(TAG, "NetworkMonitor started")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to register network callback", ex)
        }
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.i(TAG, "NetworkMonitor stopped")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to unregister network callback", ex)
        }
    }

    fun isConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}