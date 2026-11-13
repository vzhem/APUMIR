package com.vladimir.messenger.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.diagnostics.Counters
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Listens to the app's default network, not every simultaneously available network. */
class NetworkMonitor(private val context: Context) {

    private val TAG = "NetworkMonitor"
    private val state = DefaultNetworkState<Network>()
    @Volatile private var lastCapabilitiesSignature: String? = null
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // A single worker serializes resets; if it is busy, only the most recent
    // pending state matters. Stopping prevents queued transitions from running.
    private val actions = DefaultNetworkActions(callbackScope, { action ->
        currentCoroutineContext().ensureActive()
        when (action) {
            DefaultNetworkState.Action.AVAILABLE -> {
                com.vladimir.messenger.data.swarm.NetworkPathSettings.applyToCore(context)
                currentCoroutineContext().ensureActive()
                com.vladimir.messenger.data.swarm.Ipv6NetworkSettings.applyToCore(context)
                currentCoroutineContext().ensureActive()
                RustBridge.onNetworkAvailable()
                currentCoroutineContext().ensureActive()
                RustBridge.triggerGossipDiscovery()
                currentCoroutineContext().ensureActive()
                com.vladimir.messenger.data.security.DeviceIdentityGossip.onProfileOrContactsChanged()
            }
            DefaultNetworkState.Action.LOST -> RustBridge.onNetworkLost()
            DefaultNetworkState.Action.POLICY ->
                com.vladimir.messenger.data.swarm.NetworkPathSettings.applyToCore(context)
            else -> Unit
        }
    }, { error -> Log.w(TAG, "Could not apply network transition", error) })

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // On Android 8+ the capabilities callback follows this one. Wait
            // for it rather than querying a potentially stale system snapshot.
            state.available(network)
        }

        override fun onLost(network: Network) {
            when (state.lost(network)) {
                DefaultNetworkState.Action.LOST -> {
                    lastCapabilitiesSignature = null
                    Log.i(TAG, "Default network lost")
                    TransferDiagnostics.count(Counters.NETWORK_CHANGES)
                    TransferDiagnostics.recordWarning("net", "потеряна активная сеть")
                    actions.submit(DefaultNetworkState.Action.LOST)
                }
                else -> Unit // The old default may be lost after a replacement appeared.
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val isBluetooth = caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            // Track underlying transports even while VPN remains the default:
            // mobile-to-Wi-Fi under the tunnel may change the usable route.
            val route = (if (isVpn) 1 else 0) or (if (isWifi) 2 else 0) or
                (if (isMobile) 4 else 0) or (if (isEthernet) 8 else 0) or
                (if (isBluetooth) 16 else 0)
            val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val action = state.capabilities(network, hasInternet, route, metered)
            if (action == DefaultNetworkState.Action.IGNORE) return

            // Only fixed capabilities/transport names are recorded: no addresses,
            // SSIDs or network identifiers enter the safe diagnostics report.
            val transport = when {
                isVpn -> "VPN"
                isWifi -> "Wi-Fi"
                isMobile -> "мобильная сеть"
                isEthernet -> "Ethernet"
                else -> "другая"
            }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val portal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val signature = "$transport/$hasInternet/$validated/$portal"
            if (signature != lastCapabilitiesSignature) {
                lastCapabilitiesSignature = signature
                TransferDiagnostics.record("net", "возможности сети: $transport, internet=$hasInternet, validated=$validated, captive_portal=$portal")
            }
            when (action) {
                DefaultNetworkState.Action.AVAILABLE -> {
                    Log.i(TAG, "Default network available or changed")
                    TransferDiagnostics.count(Counters.NETWORK_CHANGES)
                    TransferDiagnostics.record("net", "сеть: $transport")
                    actions.submit(action)
                }
                DefaultNetworkState.Action.LOST -> {
                    Log.i(TAG, "Default network has no internet capability")
                    TransferDiagnostics.count(Counters.NETWORK_CHANGES)
                    TransferDiagnostics.recordWarning("net", "активная сеть без интернета")
                    actions.submit(action)
                }
                DefaultNetworkState.Action.POLICY -> {
                    Log.i(TAG, "Default network metering changed; refreshing policy only")
                    actions.submit(action)
                }
                else -> Unit
            }
        }
    }

    fun start() {
        try {
            // minSdk is 26; default callback is available since Android 7.
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            Log.i(TAG, "NetworkMonitor started")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to register default network callback", ex)
        }
    }

    fun stop() {
        // Reject queued callbacks immediately; an already running blocking
        // call cannot be interrupted, but must not continue into later calls.
        actions.stop()
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
