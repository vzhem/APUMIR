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
        _name.value = normalize(prefs.getString(KEY, null))
        _conflict.value = prefs.getBoolean(CONFLICT_KEY, false)
    }

    fun set(context: Context, value: String?) {
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

    /** Храним без собаки и без пробелов. */
    fun normalize(raw: String?): String? {
        val v = raw?.trim()?.trimStart('@')?.trim()?.takeIf { it.isNotEmpty() }
        return v
    }
}
