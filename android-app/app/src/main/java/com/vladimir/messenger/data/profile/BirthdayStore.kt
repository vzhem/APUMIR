package com.vladimir.messenger.data.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Мой день рождения и разрешение показывать его друзьям.
 *
 * Лежит в тех же настройках, что имя, @никнейм и аватар (`p2p_prefs`), поэтому
 * уезжает вместе с резервной копией профиля. Скрытая дата не покидает телефон:
 * рассылка (`GroupRepository.publishMyBirthday`) отправляет только видимую дату,
 * а при выключении галочки посылает короткий отзыв — иначе у друзей осталась бы
 * старая дата навсегда.
 */
object BirthdayStore {
    private const val PREFS = "p2p_prefs"
    private const val KEY_DAY = "my_birthday_day"
    private const val KEY_MONTH = "my_birthday_month"
    private const val KEY_YEAR = "my_birthday_year"
    private const val KEY_VISIBLE = "my_birthday_visible"

    private val _mine = MutableStateFlow<Birthday?>(null)

    /** Моя дата (null — не задана). Поток нужен, чтобы рассылка шла сразу. */
    val mine: StateFlow<Birthday?> = _mine.asStateFlow()

    /** Показывать её друзьям. Выключено — значит и в рассылку не попадёт. */
    fun isVisible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VISIBLE, false) && read(context) != null

    fun init(context: Context) {
        _mine.value = read(context)
    }

    fun read(context: Context): Birthday? {
        val prefs = prefs(context)
        val day = prefs.getInt(KEY_DAY, 0)
        val month = prefs.getInt(KEY_MONTH, 0)
        if (!Birthday.isValid(day, month)) return null
        return Birthday(
            day = day,
            month = month,
            year = prefs.getInt(KEY_YEAR, Birthday.NO_YEAR).takeIf {
                Birthday.isValidYear(it, java.time.LocalDate.now().year)
            } ?: Birthday.NO_YEAR,
            visible = prefs.getBoolean(KEY_VISIBLE, false),
        )
    }

    /**
     * Записать дату, выбранную в профиле.
     *
     * Видимость задаётся отдельно: человек ставит дату и тут же решает галочкой,
     * увидят ли её друзья.
     */
    fun set(context: Context, day: Int, month: Int, year: Int = Birthday.NO_YEAR, visible: Boolean) {
        if (!Birthday.isValid(day, month)) return
        val nowYear = java.time.LocalDate.now().year
        prefs(context).edit()
            .putInt(KEY_DAY, day)
            .putInt(KEY_MONTH, month)
            .putInt(KEY_YEAR, year.takeIf { Birthday.isValidYear(it, nowYear) } ?: Birthday.NO_YEAR)
            .putBoolean(KEY_VISIBLE, visible)
            .apply()
        _mine.value = read(context)
    }

    /** Галочка «дата видна друзьям»: саму дату не трогает. */
    fun setVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_VISIBLE, visible).apply()
        _mine.value = read(context)
    }

    /** Убрать дату совсем: у друзей она тоже исчезнет (уйдёт отзыв). */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_DAY)
            .remove(KEY_MONTH)
            .remove(KEY_YEAR)
            .remove(KEY_VISIBLE)
            .apply()
        _mine.value = read(context)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
