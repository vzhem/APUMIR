package com.vladimir.messenger.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Запись в «Избранном» — личном хранилище абонента.
 *
 * Хранится только описание: текст заметки и, для файла, ссылка на уже принятую
 * передачу (`transferId`). Сам файл лежит там же, где и был, — копии не
 * делаются, иначе телефон быстро забился бы дублями фото и видео.
 *
 * `sourceTitle` — откуда сохранено («Группа Стройка», «Канал Новости», имя
 * собеседника): человеку важно помнить происхождение поста.
 */
@Entity(
    tableName = "saved_items",
    indices = [Index(value = ["savedAtMs"])],
)
data class SavedItemEntity(
    @PrimaryKey val id: String,
    /** TEXT - заметка или пересланный пост, FILE - ссылка на принятый файл. */
    val kind: String,
    val text: String,
    /** Для kind = FILE: transferId в таблице file_transfers. */
    val transferId: String? = null,
    val fileName: String = "",
    val mediaType: String = "",
    val sizeBytes: Long = 0,
    /** Откуда сохранено: имя группы, канала или собеседника. Пусто - своя заметка. */
    val sourceTitle: String = "",
    val savedAtMs: Long,
    // ── Обратная ссылка на оригинал (схема 14 → 15) ──────────────────────────
    // Нужна, чтобы из «Избранного» открыть то самое место, откуда сохранено.
    // Пустые значения означают старую запись или свою заметку - тогда переход
    // просто не предлагается.
    /** CHAT | GROUP | CHANNEL, пусто - перехода нет. */
    @ColumnInfo(defaultValue = "")
    val originKind: String = "",
    /** chatId личного чата, groupId группы или channelId канала. */
    @ColumnInfo(defaultValue = "")
    val originId: String = "",
    /** Тема группы или пост канала - чтобы открыть именно её. */
    @ColumnInfo(defaultValue = "")
    val originTopicId: String = "",
    /** Имя собеседника: нужно маршруту личного чата. */
    @ColumnInfo(defaultValue = "")
    val originName: String = "",
    /** Адрес собеседника: тоже часть маршрута личного чата. */
    @ColumnInfo(defaultValue = "")
    val originContactId: String = "",
    // ── Фотографии поста (схема 17 → 18) ─────────────────────────────────────
    // Пост канала с фотографиями сохраняется вместе с ними: строки base64
    // (см. InlineImage), разделённые переводом строки. Это единственное
    // место, где «Избранное» хранит копию, а не ссылку: фото поста маленькие
    // (до ~5 КБ каждое), а оригинал пропадает вместе с подпиской на канал.
    @ColumnInfo(defaultValue = "")
    val photos: String = "",
    // ── Закрепление (схема 20 → 21) ─────────────────────────────────────────
    // Закреплённое всегда сверху списка; среди закреплённых - по времени
    // закрепления. Миграция честная (ALTER TABLE), данные не теряем.
    /** Закреплено в «Избранном». */
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false,
    /** Когда закреплено (0 - не закреплено): порядок среди закреплённых. */
    @ColumnInfo(defaultValue = "0")
    val pinnedAtMs: Long = 0,
) {
    /** Фотографии по порядку; пусто, если их нет. Функция, а не свойство: Room не должен видеть в ней колонку. */
    fun photoList(): List<String> =
        if (photos.isBlank()) emptyList() else photos.split('\n').filter { it.isNotBlank() }

    companion object {
        /** Собрать колонку из списка фотографий. */
        fun joinPhotos(images: List<String>): String =
            images.filter { it.isNotBlank() }.joinToString("\n")
    }
}
