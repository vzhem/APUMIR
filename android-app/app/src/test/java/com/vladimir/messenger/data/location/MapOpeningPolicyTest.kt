package com.vladimir.messenger.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MapOpeningPolicyTest {
    private val point = MapPoint(56.320886, 43.856481)

    @Test
    fun askIsTheDefaultAndUnknownPreferencesFailSafely() {
        assertEquals(MapApp.ASK, MapOpeningPolicy.appFromId(null))
        assertEquals(MapApp.ASK, MapOpeningPolicy.appFromId("removed-provider"))
        assertEquals("ask", MapOpeningPolicy.DEFAULT_APP_ID)
    }

    @Test
    fun everyPreferenceHasAStableDistinctIdAndRoundTrips() {
        assertEquals(MapApp.entries.size, MapApp.entries.map { it.id }.distinct().size)
        MapApp.entries.forEach { assertEquals(it, MapOpeningPolicy.appFromId(it.id)) }
    }

    @Test
    fun nativeProvidersUseTheirExactAndroidPackages() {
        assertEquals("ru.yandex.yandexmaps", MapApp.YANDEX.packageName)
        assertEquals("com.google.android.apps.maps", MapApp.GOOGLE.packageName)
        assertEquals("ru.dublgis.dgismobile", MapApp.TWO_GIS.packageName)
        assertNull(MapApp.ASK.packageName)
        assertNull(MapApp.BROWSER.packageName)
    }

    @Test
    fun standardGeoUsesLatitudeThenLongitudeWithoutACaption() {
        assertEquals("geo:56.320886,43.856481?q=56.320886,43.856481&z=17", MapOpeningPolicy.geoUri(point))
        assertEquals(MapOpeningPolicy.geoUri(point), MapOpeningPolicy.appUri(MapApp.GOOGLE, point))
    }

    @Test
    fun yandexPlacesAPinUsingLongitudeThenLatitude() {
        assertEquals(
            "yandexmaps://maps.yandex.ru/?pt=43.856481,56.320886&z=17&l=map",
            MapOpeningPolicy.appUri(MapApp.YANDEX, point),
        )
    }

    @Test
    fun twoGisOpensAnArbitraryPointUsingLongitudeThenLatitude() {
        assertEquals("https://2gis.ru/geo/43.856481,56.320886", MapOpeningPolicy.appUri(MapApp.TWO_GIS, point))
    }

    @Test
    fun webFallbackIsAnExplicitYandexMapsPointLink() {
        assertEquals(
            "https://yandex.ru/maps/?pt=43.856481,56.320886&z=17&l=map",
            MapOpeningPolicy.webUrl(point),
        )
        assertEquals(MapOpeningPolicy.webUrl(point), MapOpeningPolicy.appUri(MapApp.BROWSER, point))
    }

    @Test
    fun coordinateFormattingDoesNotDependOnThePhoneLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val negative = MapPoint(-33.865143, -70.654321)
            assertEquals(
                "yandexmaps://maps.yandex.ru/?pt=-70.654321,-33.865143&z=17&l=map",
                MapOpeningPolicy.appUri(MapApp.YANDEX, negative),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun invalidCoordinatesNeverProduceAnExternalLink() {
        val invalid = listOf(
            MapPoint(Double.NaN, 0.0), MapPoint(0.0, Double.POSITIVE_INFINITY),
            MapPoint(90.01, 0.0), MapPoint(-90.01, 0.0),
            MapPoint(0.0, 180.01), MapPoint(0.0, -180.01),
        )
        invalid.forEach { point ->
            assertFalse(MapOpeningPolicy.validPoint(point))
            assertThrows(IllegalArgumentException::class.java) { MapOpeningPolicy.geoUri(point) }
            assertThrows(IllegalArgumentException::class.java) { MapOpeningPolicy.appUri(MapApp.YANDEX, point) }
        }
        assertTrue(MapOpeningPolicy.validPoint(MapPoint(90.0, -180.0)))
        assertTrue(MapOpeningPolicy.validPoint(MapPoint(-90.0, 180.0)))
    }
}
