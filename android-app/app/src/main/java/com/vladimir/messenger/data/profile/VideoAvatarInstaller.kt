package com.vladimir.messenger.data.profile

import android.content.Context
import com.vladimir.messenger.data.story.StoryVideoExporter
import com.vladimir.messenger.ui.theme.AvatarHolder
import com.vladimir.messenger.util.AvatarFiles
import com.vladimir.messenger.util.copyWithLimit
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Установка видеоаватара из готового экспорта редактора.
 *
 * Редактор видеоаватара тот же, что у видеоистории, поэтому сюда приходит
 * обычный [StoryVideoExporter.Exported]: файл H.264/AAC и JPEG выбранного кадра.
 * Дальше три вещи, которых у истории нет:
 *
 *  1. **Ранг.** [VideoAvatarRankGate] проверяется ЗДЕСЬ, а не только на кнопке:
 *     экран можно обойти, а сохранённый файл переживёт экран.
 *  2. **Пределы аватара.** [VideoAvatarPolicy] — 15 секунд и 24 МБ: экспорт
 *     видеоистории длиннее, и молча поставить минутный ролик аватаром нельзя.
 *  3. **Постер наружу, видео себе.** Кадр становится обычным аватаром
 *     (`AvatarHolder`, JPEG уезжает собеседникам и второму устройству), а ролик
 *     остаётся в приватной папке и играет только у владельца.
 *
 * `internal`: на входе [StoryVideoExporter.Exported] — внутренний тип
 * видеоисторий, и публичная функция не вправе его показывать (компилятор
 * Kotlin это ловит: «Function 'public' exposes its 'internal' parameter
 * type»). Внутри модуля приложения установщик виден всем, кому нужен.
 */
internal object VideoAvatarInstaller {

    /** Что установлено: путь к ролику, URI постера и выбор из редактора. */
    data class Installed(
        val path: String,
        val posterUri: String?,
        val caption: String,
        val circle: Boolean,
    )

    /**
     * Сохранить видеоаватар. Файлы экспорта остаются у вызывающего (он их и
     * удаляет): здесь они копируются, поэтому удаление черновика после
     * установки не ломает готовый аватар.
     */
    suspend fun install(context: Context, exported: StoryVideoExporter.Exported): Installed =
        withContext(Dispatchers.IO) {
            VideoAvatarRankGate.requireCanSet(context)
            VideoAvatarPolicy.requireAcceptable(exported.draft, exported.media)
            check(exported.file.isFile) { "Экспорт видеоаватара исчез до сохранения" }

            val target = AvatarFiles.videoTarget(context)
            var stored = false
            try {
                exported.file.inputStream().use { input ->
                    target.outputStream().use { out ->
                        // Потолок объявлен рядом с копированием (правило чтения
                        // файлов с ограничением): экспорт уже проверен, но файл
                        // мог быть подменён между проверкой и копией.
                        check(copyWithLimit(input, out, VideoAvatarPolicy.MAX_EXPORT_BYTES)) {
                            "Файл видеоаватара больше ${VideoAvatarPolicy.MAX_EXPORT_BYTES} байт"
                        }
                    }
                }
                stored = true
                val posterUri = AvatarFiles.saveCoverFile(context, exported.cover)
                AvatarFiles.pruneVideos(context, keep = target)
                // Постер ставится обычным аватаром: его увидят собеседники и
                // второе устройство. Без постера аватар остаётся прежним.
                posterUri?.let { AvatarHolder.set(context, it) }
                VideoAvatarStore.set(
                    context,
                    path = target.absolutePath,
                    caption = exported.draft.caption,
                    circle = exported.draft.circle,
                )
                Installed(
                    path = target.absolutePath,
                    posterUri = posterUri,
                    caption = exported.draft.caption,
                    circle = exported.draft.circle,
                )
            } finally {
                // Неудачная установка не оставляет на диске половину аватара.
                if (!stored) target.delete()
            }
        }

    /** Убрать видеоаватар. Обычная картинка (постер) остаётся. */
    fun clear(context: Context) {
        val previous: File? = VideoAvatarStore.snapshot().path?.let(::File)
        VideoAvatarStore.clear(context)
        previous?.takeIf { it.isFile }?.delete()
    }
}
