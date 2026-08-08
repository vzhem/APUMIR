package com.vladimir.messenger.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi

object BatteryOptimizer {

    /**
     * роверяет, отключена ли оптимизация батареи для нашего приложения
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // а старых версиях не актуально
        }
    }

    /**
     * апрашивает отключение оптимизации батареи
     * : требует разрешения REQUEST_IGNORE_BATTERY_OPTIMIZATIONS в Manifest
     */
    @SuppressLint("BatteryLife")
    @RequiresApi(Build.VERSION_CODES.M)
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }

    /**
     * ткрывает системные настройки оптимизации батареи
     */
    fun openBatterySettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        context.startActivity(intent)
    }
}

/**
 * енеджер WakeLock для удержания CPU активным во время P2P операций
 */
class WakeLockManager(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    fun acquire(tag: String = "P2PMessenger:WakeLock") {
        if (wakeLock == null || wakeLock?.isHeld == false) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                tag
            ).apply {
                acquire() // ез таймаута — управляем вручную
            }
        }
    }

    fun release() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    fun isHeld(): Boolean = wakeLock?.isHeld == true
}
