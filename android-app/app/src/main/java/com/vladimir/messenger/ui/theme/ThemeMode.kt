package com.vladimir.messenger.ui.theme

import com.vladimir.messenger.R
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Выбор темы в настройках: день, ночь или как в системе. */
enum class ThemeMode(val storedValue: String, @androidx.annotation.StringRes val labelRes: Int) {
    SYSTEM("system", R.string.settings_theme_auto),
    LIGHT("light", R.string.settings_theme_day),
    DARK("dark", R.string.settings_theme_night),
    ;

    companion object {
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/**
 * Хранит выбор темы вне Compose, чтобы MainActivity и экран настроек видели
 * одно и то же значение. Сохраняется в p2p_prefs под ключом theme_mode,
 * поэтому переживает перезапуск приложения.
 */
object ThemeModeHolder {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "theme_mode"

    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /** Вызывается в MainActivity.onCreate до setContent. */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mode.value = ThemeMode.fromStored(prefs.getString(KEY, null))
    }

    /** Вызывается из настроек: пишет в prefs и сразу переключает приложение. */
    fun set(context: Context, value: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value.storedValue).apply()
        _mode.value = value
        // р246: живой профиль - тема совпадает на обоих устройствах личности.
        com.vladimir.messenger.data.mirror.ProfileMirror.noteLocalChange(
            context, com.vladimir.messenger.data.mirror.ProfileMirror.FIELD_THEME,
        )
    }
}
