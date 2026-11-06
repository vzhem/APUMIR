package com.vladimir.messenger.data.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Свой видеоаватар: короткий ролик в приватной папке приложения плюс то, что
 * выбрал человек в редакторе (кадр-постер, подпись, форма).
 *
 * Живьём видео играет там, где стою я сам. Для всех списков и собеседников
 * картинкой остаётся кадр-постер, который ставится обычным аватаром
 * одновременно с видео: сеть аватаров уже умеет JPEG, и чужие телефоны не
 * обязаны уметь видео. Подпись тоже остаётся на этом устройстве — наружу уходит
 * только постер.
 */
object VideoAvatarStore {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "my_video_avatar_path"
    private const val KEY_CAPTION = "my_video_avatar_caption"
    private const val KEY_CIRCLE = "my_video_avatar_circle"

    /** Что лежит в хранилище: файл, подпись из редактора и форма показа. */
    data class State(
        val path: String?,
        val caption: String,
        val circle: Boolean,
    ) {
        val isActive: Boolean get() = !path.isNullOrBlank()
    }

    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()

    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption.asStateFlow()

    /**
     * Круг или обычный формат. Как и в историях, это маска показа, а не обрезка
     * файла: круг показывает центральную часть кадра, обычный сохраняет
     * пропорции внутри круглой рамки аватара.
     */
    private val _circle = MutableStateFlow(true)
    val circle: StateFlow<Boolean> = _circle.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _path.value = prefs.getString(KEY, null).orEmpty().ifBlank { null }
        _caption.value = prefs.getString(KEY_CAPTION, null).orEmpty()
        _circle.value = prefs.getBoolean(KEY_CIRCLE, true)
    }

    /** Снимок хранилища: нужен установщику и проверкам, где поток излишен. */
    fun snapshot(): State = State(_path.value, _caption.value, _circle.value)

    /**
     * Записать видеоаватар. [path] = null убирает видео (обычная картинка
     * остаётся): подпись и форма чистятся вместе с ним, чтобы следующий аватар
     * не унаследовал чужую подпись.
     */
    fun set(context: Context, path: String?, caption: String = "", circle: Boolean = true) {
        val active = path?.takeIf { it.isNotBlank() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, active.orEmpty())
            .putString(KEY_CAPTION, if (active == null) "" else caption.take(VideoAvatarPolicy.MAX_CAPTION_CHARS))
            .putBoolean(KEY_CIRCLE, circle)
            .apply()
        _path.value = active
        _caption.value = if (active == null) "" else caption.take(VideoAvatarPolicy.MAX_CAPTION_CHARS)
        _circle.value = circle
        // Контакты узнают о новом ролике (или его снятии) сразу, а не к следующему
        // анонсу: мост к координатору обмена видеоаватарами.
        com.vladimir.messenger.data.avatavideo.AvatarVideoGossip.onMyVideoChanged(context)
    }

    /** Убрать видеоаватар, не трогая обычную картинку. */
    fun clear(context: Context) = set(context, null)
}
