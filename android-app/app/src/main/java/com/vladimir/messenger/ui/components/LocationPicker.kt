package com.vladimir.messenger.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vladimir.messenger.R
import com.vladimir.messenger.data.location.CurrentLocation
import com.vladimir.messenger.util.LocationShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Выбор точки на карте, как в топовых мессенджерах: карта OpenStreetMap на
 * весь экран, метка закреплена по центру - карта ездит под ней. Можно
 * отправить своё местоположение, любую точку (кафе, вход, встречу) или найти
 * место поиском (Nominatim). Точность ±N м передаётся только для собственной
 * точки: у выбранной вручную её нет.
 */
@Composable
fun LocationPickerDialog(
    ownFix: CurrentLocation.Fix,
    onDismiss: () -> Unit,
    onSend: (lat: Double, lon: Double, accuracyM: Int?, label: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var centerLat by remember { mutableStateOf(ownFix.lat) }
    var centerLon by remember { mutableStateOf(ownFix.lon) }
    var isOwnPoint by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(16.5)
            controller.setCenter(GeoPoint(ownFix.lat, ownFix.lon))
            onResume()
        }
    }
    DisposableEffect(mapView) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                val center = mapView.mapCenter
                centerLat = center.latitude
                centerLon = center.longitude
                isOwnPoint = isOwnPoint && distanceM(centerLat, centerLon, ownFix.lat, ownFix.lon) <
                    (ownFix.accuracyM ?: 30) + 25
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean = false
        }
        mapView.addMapListener(listener)
        onDispose {
            mapView.removeMapListener(listener)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    fun moveTo(lat: Double, lon: Double, own: Boolean, name: String?) {
        mapView.controller.animateTo(GeoPoint(lat, lon))
        centerLat = lat
        centerLon = lon
        isOwnPoint = own
        if (name != null) label = name
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFE8E4DA)),
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
            )

            // Метка по центру: остриё указывает на выбранную точку.
            Icon(
                Icons.Filled.Place,
                contentDescription = stringResource(R.string.loc_card_title),
                tint = Color(0xFFE5484D),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-22).dp)
                    .size(44.dp),
            )

            // Верхняя панель: заголовок, закрытие, поиск места.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.96f))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.loc_pick_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E2430),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.loc_cancel),
                            tint = Color(0xFF5A6472),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        ApuFormTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = stringResource(R.string.loc_search_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IconButton(
                        onClick = {
                            val query = searchQuery.trim()
                            if (query.length < 3 || searching) return@IconButton
                            searching = true
                            searchNote = null
                            scope.launch {
                                val result = runCatching { geocode(context, query) }.getOrNull()
                                searching = false
                                when {
                                    result == null ->
                                        searchNote = context.getString(R.string.loc_search_failed)
                                    result.lat == null ->
                                        searchNote = context.getString(R.string.loc_search_not_found)
                                    else -> moveTo(result.lat, result.lon!!, own = false, name = result.name)
                                }
                            }
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        if (searching) {
                            ApuPremiumSpinner(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.loc_search_action),
                                tint = Color(0xFF5A6472),
                            )
                        }
                    }
                }
                searchNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE5484D),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Кнопка «я здесь» - вернуть карту к собственному местоположению.
            IconButton(
                onClick = { moveTo(ownFix.lat, ownFix.lon, own = true, name = null) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 196.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.96f)),
            ) {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.loc_my_location),
                    tint = Color(0xFF5A6472),
                )
            }

            // Нижняя карточка: что именно уйдёт, подпись, отправка.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.97f))
                    .padding(14.dp),
            ) {
                Text(
                    if (isOwnPoint) {
                        stringResource(R.string.loc_my_location)
                    } else {
                        stringResource(R.string.loc_chosen_point)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF5A6472),
                )
                Text(
                    LocationShare.coordsText(
                        LocationShare.Shared(centerLat, centerLon, null, ""),
                    ) + if (isOwnPoint && ownFix.accuracyM != null) {
                        " · ±" + ownFix.accuracyM + " м"
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1E2430),
                )
                Spacer(Modifier.height(8.dp))
                ApuFormTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = stringResource(R.string.loc_dialog_label_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    ApuTextAction(
                        label = stringResource(R.string.loc_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(10.dp))
                    ApuTextAction(
                        label = stringResource(R.string.loc_send_point),
                        onClick = {
                            onSend(
                                centerLat,
                                centerLon,
                                if (isOwnPoint) ownFix.accuracyM else null,
                                label.trim(),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private data class GeocodeResult(val lat: Double?, val lon: Double?, val name: String?)

/** Поиск места по названию/адресу (Nominatim OpenStreetMap, нужен интернет). */
private suspend fun geocode(context: Context, query: String): GeocodeResult =
    withContext(Dispatchers.IO) {
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=ru&q=" +
                URLEncoder.encode(query, "UTF-8"),
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "ApuMessenger/" + context.packageName)
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = org.json.JSONArray(body)
            if (array.length() == 0) {
                GeocodeResult(null, null, null)
            } else {
                val item = array.getJSONObject(0)
                GeocodeResult(
                    lat = item.optDouble("lat", Double.NaN).takeIf { !it.isNaN() },
                    lon = item.optDouble("lon", Double.NaN).takeIf { !it.isNaN() },
                    name = item.optString("display_name")
                        .substringBefore(",")
                        .take(80)
                        .ifBlank { null },
                )
            }
        } finally {
            connection.disconnect()
        }
    }

private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    val results = FloatArray(1)
    android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
    return results[0].toInt()
}
