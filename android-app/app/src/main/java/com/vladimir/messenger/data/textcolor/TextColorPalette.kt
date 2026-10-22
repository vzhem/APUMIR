package com.vladimir.messenger.data.textcolor

import androidx.compose.ui.graphics.Color
import com.vladimir.messenger.R

/**
 * Fixed, high-contrast colours used for message text and display names.
 *
 * A fixed palette keeps the colour readable on both APU message surfaces and
 * avoids sending arbitrary low-contrast colours over the wire. The empty id is
 * deliberately the default: old messages and old clients keep their theme
 * colour.
 */
object TextColorPalette {
    const val DEFAULT_ID = ""

    data class Choice(
        val id: String,
        val argb: Long,
        val labelRes: Int,
    )

    val choices: List<Choice> = listOf(
        Choice(DEFAULT_ID, 0xFF1E2430L, R.string.text_color_default),
        Choice("red", 0xFFC62828L, R.string.text_color_red),
        Choice("orange", 0xFFEF6C00L, R.string.text_color_orange),
        Choice("green", 0xFF2E7D32L, R.string.text_color_green),
        Choice("teal", 0xFF00695CL, R.string.text_color_teal),
        Choice("blue", 0xFF1565C0L, R.string.text_color_blue),
        Choice("indigo", 0xFF4527A0L, R.string.text_color_indigo),
        Choice("purple", 0xFF6A1B9AL, R.string.text_color_purple),
        Choice("pink", 0xFFAD1457L, R.string.text_color_pink),
        Choice("brown", 0xFF6D4C41L, R.string.text_color_brown),
    )

    private val byId = choices.associateBy { it.id }

    fun normalize(id: String?): String = id.orEmpty().takeIf { byId.containsKey(it) } ?: DEFAULT_ID

    fun choice(id: String?): Choice = byId[normalize(id)] ?: choices.first()

    /** Resolve a stored palette id, falling back to the surface's normal ink. */
    fun colorOr(id: String?, fallback: Color): Color =
        if (normalize(id) == DEFAULT_ID) fallback else Color(choice(id).argb)
}
