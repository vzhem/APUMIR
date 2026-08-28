package com.vladimir.messenger.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Свои обои чатов: URI картинки, выбранной в галерее. null - стандартная
 * подложка в тон теме. Сохраняется в p2p_prefs, переживает перезапуск.
 */
object WallpaperHolder {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "custom_wallpaper_uri"

    private val _uri = MutableStateFlow<String?>(null)
    val uri: StateFlow<String?> = _uri.asStateFlow()

    fun init(context: Context) {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null).orEmpty()
        _uri.value = value.ifBlank { null }
    }

    fun set(context: Context, uri: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, uri.orEmpty()).apply()
        _uri.value = uri?.takeIf { it.isNotBlank() }
    }
}
