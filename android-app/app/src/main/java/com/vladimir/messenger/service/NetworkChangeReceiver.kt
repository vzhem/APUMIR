package com.vladimir.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Отслеживает смену сети (WiFi → Mobile, потеря связи, восстановление).
 * При смене сети → перезапускает CoreServerService для reconnect.
 */
class NetworkChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkChange"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: run {
                Log.w(TAG, "Network lost!")
                return
            }
            val caps = cm.getNetworkCapabilities(network) ?: return
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Unknown"
            }
            Log.i(TAG, "Network changed: $type")
            // Перезапуск сервиса для reconnect с новым IP
            val svcIntent = Intent(context, CoreServerService::class.java)
            context.stopService(svcIntent)
            context.startForegroundService(svcIntent)
        }
    }
}