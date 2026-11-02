package com.vladimir.messenger.data.location

import java.util.Locale

/** A local device preference, never a field in a location message or profile mirror. */
enum class MapApp(val id: String, val packageName: String?) {
    ASK("ask", null),
    YANDEX("yandex", "ru.yandex.yandexmaps"),
    GOOGLE("google", "com.google.android.apps.maps"),
    TWO_GIS("two_gis", "ru.dublgis.dgismobile"),
    BROWSER("browser", null),
}

data class MapPoint(val lat: Double, val lon: Double)

/**
 * Pure URL rules for opening a point in an external map. No Android calls,
 * network requests or automatic launches here. Yandex/2GIS use lon,lat;
 * the standard geo URI uses lat,lon. Captions and accuracy stay inside APU.
 */
object MapOpeningPolicy {
    const val DEFAULT_APP_ID = "ask"
    private const val ZOOM = 17

    fun appFromId(id: String?): MapApp =
        MapApp.entries.firstOrNull { it.id == id } ?: MapApp.ASK

    fun validPoint(point: MapPoint): Boolean =
        point.lat.isFinite() && point.lon.isFinite() &&
            point.lat in -90.0..90.0 && point.lon in -180.0..180.0

    fun geoUri(point: MapPoint): String {
        val latLon = latLon(point)
        return "geo:$latLon?q=$latLon&z=$ZOOM"
    }

    fun appUri(app: MapApp, point: MapPoint): String = when (app) {
        MapApp.ASK, MapApp.GOOGLE -> geoUri(point)
        // pt places a pin; ll alone would only centre the map.
        MapApp.YANDEX -> "yandexmaps://maps.yandex.ru/?pt=${lonLat(point)}&z=$ZOOM&l=map"
        // Official Android deeplink for an arbitrary point, restricted to the 2GIS package.
        MapApp.TWO_GIS -> "https://2gis.ru/geo/${lonLat(point)}"
        MapApp.BROWSER -> webUrl(point)
    }

    fun webUrl(point: MapPoint): String =
        "https://yandex.ru/maps/?pt=${lonLat(point)}&z=$ZOOM&l=map"

    private fun latLon(point: MapPoint): String {
        require(validPoint(point)) { "Invalid map coordinates" }
        return "${fmt(point.lat)},${fmt(point.lon)}"
    }

    private fun lonLat(point: MapPoint): String {
        require(validPoint(point)) { "Invalid map coordinates" }
        return "${fmt(point.lon)},${fmt(point.lat)}"
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value)
}
