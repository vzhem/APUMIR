package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.location.MapApp
import com.vladimir.messenger.data.location.MapAppPreferenceStore
import com.vladimir.messenger.data.location.MapPoint
import com.vladimir.messenger.util.MapAppLauncher

/**
 * Shared by received location cards and the point picker. The returned action
 * is called only from a click handler; composition never opens an external app.
 * A missing/broken default offers explicit alternatives instead of silently
 * sending the point to a different provider.
 */
@Composable
fun rememberOpenMapAction(): (Double, Double) -> Unit {
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.map_chooser_title)
    var issue by remember { mutableStateOf<MapLaunchIssue?>(null) }

    fun report(point: MapPoint, app: MapApp, result: MapAppLauncher.Result) {
        issue = if (result == MapAppLauncher.Result.OPENED) null else MapLaunchIssue(point, app, result)
    }

    val action: (Double, Double) -> Unit = { lat, lon ->
        val point = MapPoint(lat, lon)
        val app = MapAppPreferenceStore.current(context)
        report(point, app, MapAppLauncher.open(context, point, app, chooserTitle))
    }

    issue?.let { current ->
        if (current.result == MapAppLauncher.Result.INVALID_POINT) {
            ApuPremiumNoticeDialog(
                title = stringResource(R.string.map_open_failed_title),
                message = stringResource(R.string.map_invalid_point),
                onDismiss = { issue = null },
            )
        } else {
            val missing = current.result == MapAppLauncher.Result.NOT_INSTALLED
            val noApp = current.result == MapAppLauncher.Result.NO_APP
            val message = when {
                missing -> stringResource(R.string.map_missing_message, mapAppLabel(current.app))
                noApp && current.app == MapApp.BROWSER -> stringResource(R.string.map_no_browser_message)
                noApp -> stringResource(R.string.map_no_apps_message)
                else -> stringResource(R.string.map_open_failed_message)
            }
            ApuPremiumDialog(
                title = stringResource(
                    when {
                        missing -> R.string.map_missing_title
                        noApp -> R.string.map_no_apps_title
                        else -> R.string.map_open_failed_title
                    },
                ),
                onDismiss = { issue = null },
                confirmLabel = stringResource(
                    if (noApp && current.app != MapApp.BROWSER) R.string.map_open_web else R.string.map_choose_other,
                ),
                onConfirm = {
                    issue = null
                    val useBrowser = noApp && current.app != MapApp.BROWSER
                    val result = if (useBrowser) {
                        MapAppLauncher.openBrowser(context, current.point, chooserTitle)
                    } else {
                        MapAppLauncher.openChooser(context, current.point, chooserTitle)
                    }
                    report(current.point, if (useBrowser) MapApp.BROWSER else MapApp.ASK, result)
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    if (!noApp || current.app == MapApp.BROWSER) {
                        ApuPremiumButton(
                            label = stringResource(R.string.map_open_web),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                issue = null
                                report(
                                    current.point,
                                    MapApp.BROWSER,
                                    MapAppLauncher.openBrowser(context, current.point, chooserTitle),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    return action
}

@Composable
fun mapAppLabel(app: MapApp): String = stringResource(
    when (app) {
        MapApp.ASK -> R.string.map_app_ask
        MapApp.YANDEX -> R.string.map_app_yandex
        MapApp.GOOGLE -> R.string.map_app_google
        MapApp.TWO_GIS -> R.string.map_app_two_gis
        MapApp.BROWSER -> R.string.map_app_browser
    },
)

private data class MapLaunchIssue(
    val point: MapPoint,
    val app: MapApp,
    val result: MapAppLauncher.Result,
)
