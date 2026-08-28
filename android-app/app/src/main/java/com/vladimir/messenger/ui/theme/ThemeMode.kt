package com.vladimir.messenger.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Выбор темы в настройках: день, ночь или как в системе. */
enum class ThemeMode(val storedValue: String, val title: String) {
    SYSTEM("system", "Авто (как в системе)"),
    LIGHT("light", "День"),
    DARK("dark", "Ночь"),
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
    }
}
