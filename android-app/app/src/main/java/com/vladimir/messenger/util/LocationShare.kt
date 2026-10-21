package com.vladimir.messenger.util

import java.util.Locale

/**
 * «Поделиться координатами»: геопозиция едет обычным зашифрованным текстовым
 * сообщением со служебной первой строкой, старые версии увидят читаемую
 * подпись-ссылку второй строкой.
 *
 * Провод: `APULOC1|<lat>|<lon>|<точность м или ->|<подпись base64url>`
 * Вторая строка: `📍 https://www.openstreetmap.org/?mlat=..&mlon=..` — чтобы
 * на старом приложении и в «Избранном» точка открывалась в браузере/картах.
 */
object LocationShare {

    const val PREFIX = "APULOC1|"

    data class Shared(
        val lat: Double,
        val lon: Double,
        val accuracyM: Int?,
        val label: String,
    )

    fun format(lat: Double, lon: Double, accuracyM: Int?, label: String = ""): String {
        val encoded = if (label.isBlank()) {
            ""
        } else {
            android.util.Base64.encodeToString(
                label.trim().take(120).toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
        }
        return PREFIX + fmt(lat) + "|" + fmt(lon) + "|" + (accuracyM?.toString() ?: "-") +
            "|" + encoded + "\n📍 " + webUrl(lat, lon)
    }

    fun parse(content: String?): Shared? {
        if (content == null) return null
        val firstLine = content.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!firstLine.startsWith(PREFIX)) return null
        val parts = firstLine.removePrefix(PREFIX).split("|")
        if (parts.size < 3) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val label = parts.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { encoded ->
            runCatching {
                String(
                    android.util.Base64.decode(
                        encoded,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
                    ),
                    Charsets.UTF_8,
                )
            }.getOrNull()
        }.orEmpty()
        return Shared(lat, lon, parts[2].toIntOrNull(), label)
    }

    fun isLocation(content: String?): Boolean = parse(content) != null

    fun coordsText(loc: Shared): String = fmt(loc.lat) + ", " + fmt(loc.lon)

    fun geoUri(loc: Shared): String {
        val q = fmt(loc.lat) + "," + fmt(loc.lon)
        val label = loc.label.ifBlank { "Точка" }
        return "geo:" + q + "?q=" + q + "(" + android.net.Uri.encode(label) + ")"
    }

    fun webUrl(lat: Double, lon: Double): String =
        "https://www.openstreetmap.org/?mlat=" + fmt(lat) + "&mlon=" + fmt(lon) +
            "#map=16/" + fmt(lat) + "/" + fmt(lon)

    private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value)
}
