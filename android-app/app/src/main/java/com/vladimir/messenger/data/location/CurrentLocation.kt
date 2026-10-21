package com.vladimir.messenger.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Одноразовое определение координат системным [LocationManager] — без
 * Google-сервисов: сначала свежая последняя известная точка, иначе один
 * свежий фикс с ожиданием до [TIMEOUT_MS].
 */
object CurrentLocation {

    data class Fix(val lat: Double, val lon: Double, val accuracyM: Int?)

    private const val TIMEOUT_MS = 20_000L
    private const val FRESH_MS = 5 * 60_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun fetch(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        @Suppress("MissingPermission")
        fun lastKnown(): Location? = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.minByOrNull { it.accuracy.takeIf { accuracy -> accuracy > 0 } ?: Float.MAX_VALUE }

        lastKnown()
            ?.takeIf { System.currentTimeMillis() - it.time < FRESH_MS && it.accuracy > 0 }
            ?.let { return it.toFix() }

        val fresh = withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (continuation.isActive) continuation.resume(location)
                        }

                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    @Suppress("MissingPermission")
                    fun request(provider: String): Boolean = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            manager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                                if (continuation.isActive) continuation.resume(location)
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        }
                        true
                    }.getOrDefault(false)
                    // GPS точнее, сеть быстрее: просим обе, побеждает первый фикс.
                    providers.forEach { request(it) }
                    continuation.invokeOnCancellation {
                        runCatching { manager.removeUpdates(listener) }
                    }
                }
            }
        }
        return (fresh ?: lastKnown())?.toFix()
    }

    private fun Location.toFix(): Fix = Fix(
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy() && accuracy > 0) min(accuracy.toInt(), 100_000) else null,
    )
}
