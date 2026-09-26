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
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return
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
        // Раунд 171: ресивер живёт на ГЛАВНОМ потоке, а stop+start сервиса
        // тянет за собой пересоздание всего стека (движок, подписки,
        // bootstrap-синхронизации). Ночью сеть прыгала - каждый скачок
        // перезапускал сервис прямо здесь, и приложение «не отвечало»
        // (владелец, скрин ANR). Теперь: дебаунс 10 секунд и перезапуск
        // в фоновом потоке (goAsync), главному потоку - только лог.
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_rx", 0L) < DEBOUNCE_MS) {
            Log.i(TAG, "Network flap debounced")
            return
        }
        prefs.edit().putLong("last_rx", now).apply()
        val pending = goAsync()
        Thread {
            try {
                val svcIntent = Intent(context, CoreServerService::class.java)
                context.stopService(svcIntent)
                context.startForegroundService(svcIntent)
            } catch (error: Exception) {
                Log.w(TAG, "service restart failed: ${error.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "NetworkChange"
        private const val PREFS_NAME = "apu_network_rx"

        /** Не чаще раза в 10 секунд - шторм смен сети гасим. */
        private const val DEBOUNCE_MS = 10_000L
    }
}