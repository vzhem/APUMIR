package com.vladimir.messenger.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.vladimir.messenger.data.location.MapApp
import com.vladimir.messenger.data.location.MapOpeningPolicy
import com.vladimir.messenger.data.location.MapPoint

/**
 * Explicit-tap-only bridge to an installed map or browser. Never sends a message,
 * requests location, downloads an app, or silently substitutes another provider.
 * All coordinates leave APU only through startActivity after the caller's tap.
 */
object MapAppLauncher {
    enum class Result { OPENED, NOT_INSTALLED, NO_APP, INVALID_POINT, FAILED }

    private const val TAG = "MapAppLauncher"

    fun open(context: Context, point: MapPoint, app: MapApp, chooserTitle: String): Result {
        if (!MapOpeningPolicy.validPoint(point)) return Result.INVALID_POINT
        return when (app) {
            MapApp.ASK -> openChooser(context, point, chooserTitle)
            MapApp.BROWSER -> openBrowser(context, point, chooserTitle)
            else -> {
                val intent = nativeIntent(point, app)
                if (!resolves(context, intent)) Result.NOT_INSTALLED else start(context, intent)
            }
        }
    }

    /** The system chooser keeps working for map apps not listed in our settings. */
    fun openChooser(context: Context, point: MapPoint, title: String): Result {
        if (!MapOpeningPolicy.validPoint(point)) return Result.INVALID_POINT
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse(MapOpeningPolicy.geoUri(point)))
        val geoPackages = activities(context, geo).map { it.activityInfo.packageName }.toSet()
        // Some installed maps handle their own scheme but not geo:. Add them once,
        // without duplicate entries when they already handle the base geo intent.
        val extras = listOf(MapApp.YANDEX, MapApp.GOOGLE, MapApp.TWO_GIS)
            .filter { it.packageName !in geoPackages }
            .map { nativeIntent(point, it) }
            .filter { resolves(context, it) }
        if (geoPackages.isEmpty() && extras.isEmpty()) return Result.NO_APP
        val chooser = Intent.createChooser(geo, title)
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        return start(context, chooser)
    }

    /** A browser preference must not accidentally open a map app through an HTTPS app link. */
    fun openBrowser(context: Context, point: MapPoint, title: String): Result {
        if (!MapOpeningPolicy.validPoint(point)) return Result.INVALID_POINT
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(MapOpeningPolicy.webUrl(point)))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val browserPackages = activities(
            context,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
        ).map { it.activityInfo.packageName }.distinct()
        if (browserPackages.isEmpty()) return Result.NO_APP
        val preferred = activities(context, web).map { it.activityInfo.packageName }
            .firstOrNull { it in browserPackages } ?: browserPackages.first()
        val intent = if (browserPackages.size == 1) {
            web.setPackage(preferred)
        } else {
            val alternatives = browserPackages.filter { it != preferred }
                .map { Intent(web).setPackage(it) }
            Intent.createChooser(Intent(web).setPackage(preferred), title)
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, alternatives.toTypedArray())
        }
        return start(context, intent)
    }

    /** Availability checks use a dummy point, never the user's actual location. */
    fun isAvailable(context: Context, app: MapApp): Boolean = when (app) {
        MapApp.ASK -> true
        MapApp.BROWSER -> activities(
            context,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
        ).isNotEmpty()
        else -> resolves(context, nativeIntent(MapPoint(0.0, 0.0), app))
    }

    private fun nativeIntent(point: MapPoint, app: MapApp): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(MapOpeningPolicy.appUri(app, point)))
            .setPackage(app.packageName)

    private fun start(context: Context, intent: Intent): Result = try {
        // Also supports a Dialog context, and returning from maps must not destroy APU.
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Result.OPENED
    } catch (failure: Exception) {
        Log.w(TAG, "Could not open the selected map", failure)
        Result.FAILED
    }

    @Suppress("DEPRECATION") // The flags=0 overload works across minSdk 26 through current Android.
    private fun activities(context: Context, intent: Intent) = try {
        context.packageManager.queryIntentActivities(intent, 0)
    } catch (failure: Exception) {
        Log.w(TAG, "Could not inspect map handlers", failure)
        emptyList()
    }

    private fun resolves(context: Context, intent: Intent): Boolean = activities(context, intent).isNotEmpty()
}
