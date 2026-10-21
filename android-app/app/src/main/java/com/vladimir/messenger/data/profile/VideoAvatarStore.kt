package com.vladimir.messenger.data.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Свой видеоаватар: путь к короткому видео в приватной папке приложения.
 *
 * Живьём видео играет только в профиле владельца (и на его втором устройстве,
 * если файл доехал зеркалом вместе с обычным аватаром). Для всех списков и
 * собеседников картинкой остаётся кадр-постер, который ставится обычным
 * аватаром одновременно с видео: сеть аватаров уже умеет JPEG, и чужие
 * телефоны не обязаны уметь видео.
 */
object VideoAvatarStore {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "my_video_avatar_path"

    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()

    fun init(context: Context) {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null).orEmpty()
        _path.value = value.ifBlank { null }
    }

    fun set(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, path.orEmpty()).apply()
        _path.value = path?.takeIf { it.isNotBlank() }
    }
}
