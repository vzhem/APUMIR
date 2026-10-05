package com.vladimir.messenger.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppFontSize(val storedValue: String, val title: String, val scale: Float) {
    SMALL("small", "Мелкий", 0.85f),
    STANDARD("standard", "Стандартный", 1.0f),
    LARGE("large", "Крупный", 1.2f),
    ;

    companion object {
        fun fromStored(value: String?): AppFontSize =
            entries.firstOrNull { it.storedValue == value } ?: STANDARD
    }
}

/** Global text size for every Compose screen, including all chats. */
object AppFontSizeHolder {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "app_font_size"
    private val _size = MutableStateFlow(AppFontSize.STANDARD)
    val size: StateFlow<AppFontSize> = _size.asStateFlow()

    fun init(context: Context) {
        _size.value = AppFontSize.fromStored(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
        )
    }

    fun set(context: Context, value: AppFontSize) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value.storedValue).apply()
        _size.value = value
    }
}
