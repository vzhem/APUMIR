package com.vladimir.messenger.ui.theme

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.vladimir.messenger.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Цветовая гамма подложки. Работает там, где стоит стандартный рисунок APU
 * (свою картинку из галереи пользователь выбирает сам и она важнее гаммы).
 * Градиент задаёт цвет, рисунок накладывается поверх как деталь. Для каждой
 * темы — свой градиент: светлый день и тёмная ночь.
 */
enum class WallpaperTint(
    val storedValue: String,
    @StringRes val labelRes: Int,
    val light: List<Color>,
    val dark: List<Color>,
) {
    /** Как было всегда: рисунок APU без цветовой подмены. */
    CLASSIC("classic", R.string.wallpaper_tint_classic, emptyList(), emptyList()),
    MINT("mint", R.string.wallpaper_tint_mint,
        listOf(Color(0xFFE3F6EE), Color(0xFFC9EBDD)), listOf(Color(0xFF0E2A24), Color(0xFF071A16))),
    LAVENDER("lavender", R.string.wallpaper_tint_lavender,
        listOf(Color(0xFFECE6FA), Color(0xFFD7CCF2)), listOf(Color(0xFF221B38), Color(0xFF140F24))),
    PEACH("peach", R.string.wallpaper_tint_peach,
        listOf(Color(0xFFFCE9DC), Color(0xFFF6D2BC)), listOf(Color(0xFF3A2219), Color(0xFF22130E))),
    SKY("sky", R.string.wallpaper_tint_sky,
        listOf(Color(0xFFE0EFFB), Color(0xFFC4DFF5)), listOf(Color(0xFF14283D), Color(0xFF0A1826))),
    ROSE("rose", R.string.wallpaper_tint_rose,
        listOf(Color(0xFFFBE3EA), Color(0xFFF3C7D4)), listOf(Color(0xFF3B1A26), Color(0xFF230F17))),
    SAND("sand", R.string.wallpaper_tint_sand,
        listOf(Color(0xFFF6EFDC), Color(0xFFE8D9B5)), listOf(Color(0xFF332A17), Color(0xFF1F190D))),
    OLIVE("olive", R.string.wallpaper_tint_olive,
        listOf(Color(0xFFEAF0D8), Color(0xFFD3DFB0)), listOf(Color(0xFF1F2A14), Color(0xFF12190B))),
    GRAPHITE("graphite", R.string.wallpaper_tint_graphite,
        listOf(Color(0xFFECEEF2), Color(0xFFD5D9E0)), listOf(Color(0xFF1E2126), Color(0xFF111316))),
    OCEAN("ocean", R.string.wallpaper_tint_ocean,
        listOf(Color(0xFFDDF5F3), Color(0xFFBDE9E6)), listOf(Color(0xFF0F2F31), Color(0xFF091C1E))),
    ;

    fun colorsFor(isDark: Boolean): List<Color> = if (isDark) dark else light

    companion object {
        fun fromStored(value: String?): WallpaperTint =
            entries.firstOrNull { it.storedValue == value } ?: CLASSIC
    }
}

/** Выбранная гамма подложки. Хранится на устройстве в p2p_prefs. */
object WallpaperTintHolder {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "wallpaper_tint"
    private val _tint = MutableStateFlow(WallpaperTint.CLASSIC)
    val tint: StateFlow<WallpaperTint> = _tint.asStateFlow()

    fun init(context: Context) {
        _tint.value = WallpaperTint.fromStored(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
        )
    }

    fun set(context: Context, value: WallpaperTint) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value.storedValue).apply()
        _tint.value = value
    }
}
