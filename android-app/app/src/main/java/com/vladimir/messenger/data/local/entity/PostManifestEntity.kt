package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Манифест поста канала (рой, этап 1; схема 18 → 19).
 *
 * Хранится ТОЛЬКО с проверенной подписью: битые и чужие манифесты в базу не
 * попадают. По манифесту телефон принимает текст и куски поста от любого
 * участника и сам отвечает на просьбы о досылке. Свои посты автор тоже
 * кладёт сюда - чтобы раздавать их манифесты дальше.
 *
 * Поля повторяют проводной формат `pman` (см. `GroupWire.buildPostManifest`);
 * `parts` - строка `id:sha16b64,…`, а не отдельная таблица: кусков ≤ 24, и
 * они нужны все разом.
 */
@Entity(
    tableName = "post_manifests",
    indices = [Index("groupId"), Index("topicId")],
)
data class PostManifestEntity(
    /** Id сообщения-поста (тот же, что в `messages`). */
    @PrimaryKey val messageId: String,
    val groupId: String,
    val topicId: String,
    val authorId: String,
    val sentAtMs: Long,
    /** hex(sha256 текста поста). */
    val textSha: String,
    /** Куски фото: `id:sha16b64,…`; пусто - фото нет. */
    val parts: String,
    /** Номер правки текста (0 - исходный пост). */
    val revision: Int,
    /** base64url открытого ключа подписанта (32 байта). */
    val signerKey: String,
    /** base64url подписи (64 байта). */
    val signature: String,
    /** Когда манифест получен или создан. */
    val receivedAtMs: Long,
)
