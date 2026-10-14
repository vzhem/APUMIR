package com.vladimir.messenger.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.diagnostics.Counters
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * NetworkMonitor — слушает изменения сети и уведомляет Rust-ядро.
 */
class NetworkMonitor(private val context: Context) {

    private val TAG = "NetworkMonitor"

    /** Тип сети для журнала: пишем только смену, а не каждый колбэк возможностей. */
    @Volatile
    private var lastTransport: String? = null
    @Volatile private var lastCapabilitiesSignature: String? = null
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available")
            TransferDiagnostics.count(Counters.NETWORK_CHANGES)
            TransferDiagnostics.record("net", "сеть появилась")
            callbackScope.launch { RustBridge.onNetworkAvailable() }
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "Network lost")
            TransferDiagnostics.count(Counters.NETWORK_CHANGES)
            TransferDiagnostics.recordWarning("net", "потеряна доступная сеть; активная сеть может быть другой")
            callbackScope.launch { RustBridge.onNetworkLost() }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities
        ) {
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            // Only fixed capability/transport changes, never addresses or network IDs.
            // These callbacks cover all matching networks, not just the active one.
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                isWifi -> "Wi-Fi"
                isMobile -> "мобильная сеть"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "другая"
            }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val portal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val signature = "$transport/$hasInternet/$validated/$portal"
            if (signature != lastCapabilitiesSignature) {
                lastCapabilitiesSignature = signature
                TransferDiagnostics.record("net", "возможности сети: $transport, internet=$hasInternet, validated=$validated, captive_portal=$portal")
            }
            if (transport != lastTransport) {
                lastTransport = transport
                TransferDiagnostics.record(
                    "net",
                    "сеть: $transport" + if (!hasInternet) ", без интернета" else "",
                )
            }
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
        callbackScope.cancel()
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
