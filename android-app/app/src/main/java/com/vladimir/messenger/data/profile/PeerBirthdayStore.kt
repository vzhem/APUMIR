package com.vladimir.messenger.data.profile

import android.content.Context
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Даты рождения друзей: приходят роевой рассылкой от самих владельцев.
 *
 * Хранилище намеренно отдельное от профиля: чужие даты не уезжают в мою
 * резервную копию и не смешиваются с моей собственной. Дата попадает сюда
 * только тогда, когда владелец сам разрешил её показывать; отзыв («скрыл»)
 * убирает запись.
 *
 * Здесь только хранение: правила слияния живут в чистом `BirthdayBook`, а
 * правило показа пузыря — в `BirthdayBanner`.
 */
object PeerBirthdayStore {
    private const val PREFS = "apu_birthdays"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_DISMISSED = "dismissed"

    private val _entries = MutableStateFlow<Map<String, PeerBirthday>>(emptyMap())

    /** Узел → объявленная дата. */
    val entries: StateFlow<Map<String, PeerBirthday>> = _entries.asStateFlow()

    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())

    /** Кого человек скрыл нажатием «×» сегодня. */
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    fun init(context: Context) {
        val prefs = prefs(context)
        val today = LocalDate.now()
        _entries.value = BirthdayBook.decode(prefs.getString(KEY_ENTRIES, null))
        val fresh = pruneDismissed(prefs.getStringSet(KEY_DISMISSED, null).orEmpty(), today)
        _dismissed.value = fresh
        if (fresh.size != prefs.getStringSet(KEY_DISMISSED, null).orEmpty().size) {
            prefs.edit().putStringSet(KEY_DISMISSED, fresh).apply()
        }
    }

    /** Объявление владельца узла: дата или отзыв («дата скрыта»). */
    fun apply(
        context: Context,
        nodeId: String,
        day: Int,
        month: Int,
        updatedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val next = BirthdayBook.apply(_entries.value, nodeId, day, month, updatedAtMs, nowMs)
        if (next == _entries.value) return
        _entries.value = next
        prefs(context).edit().putString(KEY_ENTRIES, BirthdayBook.encode(next)).apply()
    }

    /** «×» на пузыре: до конца сегодняшнего дня этот человек больше не показывается. */
    fun dismiss(context: Context, nodeId: String, today: LocalDate = LocalDate.now()) {
        val key = BirthdayBanner.dismissKey(nodeId, today)
        val next = pruneDismissed(_dismissed.value, today) + key
        _dismissed.value = next
        prefs(context).edit().putStringSet(KEY_DISMISSED, next).apply()
    }

    /** Ключи скрытия живут один день: вчерашние не нужны и только копят мусор. */
    private fun pruneDismissed(keys: Set<String>, today: LocalDate): Set<String> {
        val suffix = "-${today.year}-${today.monthValue}-${today.dayOfMonth}"
        return keys.filter { it.endsWith(suffix) }.toSet()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
