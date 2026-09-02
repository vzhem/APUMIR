package com.vladimir.messenger.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Моё @имя и флаг конфликта имён. Имя храним БЕЗ собаки - собака неснимаемый
 * префикс в полях ввода и при показе. Конфликт поднимает роевой реестр, когда
 * такое же имя оказалось у кого-то с более ранней регистрацией: тогда имя
 * снимается и приложение предлагает задать новое.
 */
object UsernameHolder {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "my_username"
    private const val CONFLICT_KEY = "username_conflict"

    private val _name = MutableStateFlow<String?>(null)
    val name: StateFlow<String?> = _name.asStateFlow()

    private val _conflict = MutableStateFlow(false)
    val conflict: StateFlow<Boolean> = _conflict.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        val normalized = normalize(stored)
        if (normalized == null && !stored.isNullOrBlank()) {
            // Имя задано до введения правила и содержит недопустимые знаки
            // (обычно кириллицу). Пробуем оставить годную часть; если не
            // осталось ничего - снимаем имя и просим задать новое тем же
            // диалогом, что и при споре за имя. Молча терять имя нельзя:
            // по нему человека узнают после переустановки.
            val salvaged = sanitize(stored).takeIf { isValid(it) }
            if (salvaged != null) {
                prefs.edit().putString(KEY, salvaged).apply()
                _name.value = salvaged
            } else {
                prefs.edit().putString(KEY, "").putBoolean(CONFLICT_KEY, true).apply()
                _name.value = null
                _conflict.value = true
                return
            }
        } else {
            _name.value = normalized
        }
        _conflict.value = prefs.getBoolean(CONFLICT_KEY, false)
    }

    fun set(context: Context, value: String?) {
        // Через set проходят оба поля ввода, поэтому негодное имя отсекается
        // здесь независимо от того, что пропустил экран.
        val normalized = normalize(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, normalized.orEmpty()).apply()
        _name.value = normalized
    }

    fun raiseConflict(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(CONFLICT_KEY, true).apply()
        _conflict.value = true
    }

    fun clearConflict(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(CONFLICT_KEY, false).apply()
        _conflict.value = false
    }

    /**
     * Разрешённые в @никнейме знаки: латинские буквы, цифры и подчёркивание.
     *
     * Ограничение не косметическое. @никнейм едет в короткой ссылке
     * `apu://a/<узел>/<никнейм>` и в QR-коде: кириллица и знаки требуют
     * URL-кодирования, где одна буква превращается в шесть символов
     * («владимир» - 48 символов вместо 8), и короткая ссылка снова
     * распухает, а QR густеет. Кроме того, по такому имени человека
     * связывают между переустановками, его диктуют голосом и набирают
     * руками - похожие кириллические и латинские буквы («о», «а», «с»)
     * делали бы два разных имени неотличимыми на вид.
     */
    private val allowed = Regex("^[A-Za-z0-9_]+$")

    /** Предел длины: столько же принимает короткая ссылка. */
    const val MAX_CHARS = 32

    /**
     * Убрать из введённого всё, что в @никнейме не разрешено.
     *
     * Применяется прямо при вводе, поэтому недопустимый знак просто не
     * появляется в поле - человек не упирается в отказ уже после «Сохранить».
     */
    fun sanitize(raw: String?): String {
        val text = raw?.trimStart('@').orEmpty()
        return buildString {
            for (ch in text) {
                if (length >= MAX_CHARS) break
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_') append(ch)
            }
        }
    }

    /** Годится ли имя: непустое и только из разрешённых знаков. */
    fun isValid(raw: String?): Boolean {
        val v = raw?.trim()?.trimStart('@')?.trim().orEmpty()
        return v.isNotEmpty() && v.length <= MAX_CHARS && allowed.matches(v)
    }

    /**
     * Храним без собаки и без пробелов. Имя со знаками вне разрешённых -
     * не имя: возвращаем null, чтобы негодное не разъехалось по сети.
     */
    fun normalize(raw: String?): String? {
        val v = raw?.trim()?.trimStart('@')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return v.takeIf { isValid(it) }
    }
}
