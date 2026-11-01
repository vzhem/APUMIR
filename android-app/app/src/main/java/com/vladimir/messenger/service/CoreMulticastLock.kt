package com.vladimir.messenger.service

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Multicast-замок Wi-Fi нужен для mDNS-обнаружения соседей в локальной сети,
 * но он не даёт Wi-Fi-радио заснуть. Держим его только пока приложение на
 * экране; в фоне уже найденные соседи и брокер продолжают доставку, а поиск
 * новых в LAN возобновляется при возврате на экран. Раньше замок жил вечно.
 */
class CoreMulticastLock(context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val lock = wifiManager?.createMulticastLock("P2PMessenger::MdnsLock")?.apply {
        setReferenceCounted(false)
    }

    @Synchronized fun sync(onScreen: Boolean) {
        val l = lock ?: return
        runCatching {
            if (onScreen && !l.isHeld) {
                l.acquire()
                Log.i(TAG, "MulticastLock acquired (foreground)")
            } else if (!onScreen && l.isHeld) {
                l.release()
                Log.i(TAG, "MulticastLock released (background)")
            }
        }.onFailure { Log.w(TAG, "multicast lock sync failed: ${it.message}") }
    }

    @Synchronized fun release() {
        runCatching {
            if (lock?.isHeld == true) lock.release()
        }.onFailure { Log.w(TAG, "multicast lock release failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "CoreMulticastLock"
    }
}
