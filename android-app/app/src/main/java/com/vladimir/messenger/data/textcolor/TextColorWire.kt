package com.vladimir.messenger.data.textcolor

import java.util.Base64

/**
 * Backward-compatible envelope for direct messages. Older clients see an
 * unknown ordinary-looking text only if they receive the prefix; therefore the
 * receiver unwraps it before any service-packet checks and stores the clean
 * message body. New clients can display the two colours.
 */
object TextColorWire {
    const val PREFIX = "APUTXT1"

    data class Decoded(
        val text: String,
        val textColor: String,
        val nameColor: String,
    )

    fun wrap(text: String, textColor: String, nameColor: String): String {
        val color = TextColorPalette.normalize(textColor)
        val name = TextColorPalette.normalize(nameColor)
        if (color.isBlank() && name.isBlank()) return text
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.toByteArray(Charsets.UTF_8))
        return "$PREFIX|$color|$name|$encoded"
    }

    fun decode(value: String): Decoded? {
        if (!value.startsWith("$PREFIX|")) return null
        val parts = value.split('|', limit = 4)
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val body = try {
            String(Base64.getUrlDecoder().decode(parts[3]), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return Decoded(
            text = body,
            textColor = TextColorPalette.normalize(parts[1]),
            nameColor = TextColorPalette.normalize(parts[2]),
        )
    }
}
