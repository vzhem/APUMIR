package com.vladimir.messenger.data.repository

// =============================================================================
// SAVEDITEMSREPOSITORY.KT — «Избранное»: личное хранилище абонента
// =============================================================================
// Одна точка входа для всех экранов: из личного чата, группы и канала в
// избранное кладут одним и тем же вызовом, поэтому поведение везде совпадает.
//
// Файлы НЕ копируются: сохраняется ссылка на уже принятую передачу
// (transferId). Копия каждого пересланного видео забила бы память телефона, а
// принятый файл и так лежит в хранилище приложения.
// =============================================================================

import com.vladimir.messenger.data.local.dao.SavedItemDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.local.entity.SavedItemEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Откуда взята сохранённая запись: по ней «Избранное» открывает оригинал.
 *
 * `kind` - CHAT, GROUP или CHANNEL. Для личного чата нужны ещё имя и адрес
 * собеседника: без них маршрут чата не собрать.
 */
data class SavedOrigin(
    val kind: String,
    val id: String,
    val topicId: String = "",
    val name: String = "",
    val contactId: String = "",
) {
    companion object {
        const val CHAT = "CHAT"
        const val GROUP = "GROUP"
        const val CHANNEL = "CHANNEL"
    }
}

/** Что получилось при сохранении - экраны показывают это человеку. */
enum class SaveResult {
    Saved,
    AlreadySaved,
    /** Файл ещё качается или передача не завершилась - сохранять нечего. */
    FileNotReady,
}

@Singleton
class SavedItemsRepository @Inject constructor(
    private val dao: SavedItemDao,
) {
    fun observeAll(): Flow<List<SavedItemEntity>> = dao.observeAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    /** Сохранить текст: заметку, пост канала или сообщение из группы. */
    suspend fun saveText(
        text: String,
        sourceTitle: String = "",
        /** Откуда сохранено, чтобы потом вернуться к оригиналу. */
        origin: SavedOrigin? = null,
    ): SaveResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SaveResult.FileNotReady
        dao.upsert(
            SavedItemEntity(
                id = UUID.randomUUID().toString(),
                kind = KIND_TEXT,
                text = trimmed,
                sourceTitle = sourceTitle,
                savedAtMs = System.currentTimeMillis(),
                originKind = origin?.kind.orEmpty(),
                originId = origin?.id.orEmpty(),
                originTopicId = origin?.topicId.orEmpty(),
                originName = origin?.name.orEmpty(),
                originContactId = origin?.contactId.orEmpty(),
            )
        )
        return SaveResult.Saved
    }

    /**
     * Сохранить файл: фото, видео, документ - что угодно.
     *
     * Незавершённую передачу не берём: ссылка указывала бы на файл, которого
     * ещё нет, и «Избранное» показывало бы пустые строки.
     */
    suspend fun saveFile(transfer: FileTransferEntity, sourceTitle: String = ""): SaveResult {
        if (transfer.state != "COMPLETE") return SaveResult.FileNotReady
        if (dao.byTransfer(transfer.transferId) != null) return SaveResult.AlreadySaved
        dao.upsert(
            SavedItemEntity(
                id = UUID.randomUUID().toString(),
                kind = KIND_FILE,
                text = transfer.displayName,
                transferId = transfer.transferId,
                fileName = transfer.displayName,
                mediaType = transfer.mediaType,
                sizeBytes = transfer.totalBytes,
                sourceTitle = sourceTitle,
                savedAtMs = System.currentTimeMillis(),
            )
        )
        return SaveResult.Saved
    }

    suspend fun delete(id: String) = dao.delete(id)

    companion object {
        const val KIND_TEXT = "TEXT"
        const val KIND_FILE = "FILE"
    }
}
