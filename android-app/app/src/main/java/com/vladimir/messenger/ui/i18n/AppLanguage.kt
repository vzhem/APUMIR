package com.vladimir.messenger.ui.i18n

// =============================================================================
// APPLANGUAGE.KT — выбор языка интерфейса
// =============================================================================
// Тексты живут в ресурсах Android: res/values (русский, по умолчанию) и
// res/values-<код>/strings.xml для остальных языков. Здесь только выбор языка.
//
// Чтобы добавить язык:
//   1. создать res/values-<код>/strings.xml с теми же именами строк, что в
//      res/values/strings.xml (недостающие строки откатываются на русский);
//   2. добавить запись в enum [AppLanguage] ниже.
// Больше ничего менять не нужно: список в настройках строится из enum.
//
// Язык хранится в p2p_prefs. Локаль применяется в MainActivity.attachBaseContext
// до создания интерфейса, поэтому stringResource сразу отдаёт нужный язык.
// Не используем AppCompat: minSdk 26, лишних зависимостей не добавляем.
// =============================================================================

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Поддерживаемые языки. [code] — BCP-47 код, по нему выбирается папка
 * values-<code>; [nativeName] — название на самом языке для списка в настройках.
 */
enum class AppLanguage(val code: String, val nativeName: String) {
    RU("ru", "Русский"),
    EN("en", "English");

    companion object {
        val DEFAULT: AppLanguage = RU

        fun fromStored(value: String?): AppLanguage =
            entries.firstOrNull { it.code == value } ?: DEFAULT
    }
}

object AppLanguageHolder {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "app_language"

    private val _language = MutableStateFlow(AppLanguage.DEFAULT)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /** Вызывается из MainActivity.attachBaseContext: возвращает контекст с выбранной локалью. */
    fun wrap(base: Context): Context {
        val stored = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        val lang = AppLanguage.fromStored(stored)
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
        _language.value = value
    }
}
