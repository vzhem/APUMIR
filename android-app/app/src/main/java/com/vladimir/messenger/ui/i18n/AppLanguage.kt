package com.vladimir.messenger.ui.i18n

// =============================================================================
// APPLANGUAGE.KT — выбор языка интерфейса (русский / English)
// =============================================================================
// Язык хранится в тех же p2p_prefs, что и тема. Локаль применяется в
// MainActivity.attachBaseContext, поэтому действует с первого кадра и не
// зависит от AppCompat (minSdk 26, лишних зависимостей не добавляем).
//
// Строки: большая часть интерфейса по-прежнему написана по-русски прямо в
// коде. Переведённые места оборачиваются в `tr(ru, en)`; остальное пока
// остаётся на русском.
// =============================================================================

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLanguage(val code: String, val nativeName: String) {
    RU("ru", "Русский"),
    EN("en", "English");

    companion object {
        fun fromStored(value: String?): AppLanguage =
            entries.firstOrNull { it.code == value } ?: RU
    }
}

object AppLanguageHolder {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "app_language"

    private val _language = MutableStateFlow(AppLanguage.RU)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /** Текущий язык для [tr]; обновляется при [wrap] и [set]. */
    @Volatile
    var current: AppLanguage = AppLanguage.RU
        private set

    /** Вызывается из MainActivity.attachBaseContext: читает язык и возвращает контекст с ним. */
    fun wrap(base: Context): Context {
        val stored = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        val lang = AppLanguage.fromStored(stored)
        current = lang
        _language.value = lang
        val locale = Locale.forLanguageTag(lang.code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Вызывается из настроек: пишет в prefs. Перезапуск активности делает вызывающий. */
    fun set(context: Context, value: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value.code).apply()
        current = value
        _language.value = value
    }
}

/** Строка на текущем языке интерфейса. */
fun tr(ru: String, en: String): String =
    if (AppLanguageHolder.current == AppLanguage.EN) en else ru
