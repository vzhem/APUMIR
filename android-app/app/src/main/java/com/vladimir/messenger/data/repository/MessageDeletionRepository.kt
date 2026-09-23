package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.MessageReactionDao
import com.vladimir.messenger.util.InlineImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Удаление своих сообщений (раунд 135).
 *
 * Просьба владельца: «чтобы можно было удалить своё сообщение у себя, и
 * выбор - удалить у всех». Два действия:
 *  - «у себя» - строка стирается локально, у собеседника остаётся;
 *  - «у всех» - собеседнику уходит короткий конверт `APUDEL1` с идентификатором
 *    сообщения (так же, как у реакций: разбирается ДО сохранения в чат), и
 *    телефон получателя стирает у себя РОВНО сообщение этого отправителя
 *    (`deleteByIdChatAndSender`) - подделать чужое удаление нельзя.
 *
 * Здесь - путь ЛИЧНЫХ чатов; группы и комментарии каналов удаляются тем же
 * способом через GroupRepository (пакет `msdel`), потому что у них свой
 * провод. Превью списка чатов пересчитывается за оставшимся последним
 * сообщением - иначе в списке болтался бы стёртый текст.
 */
@Singleton
class MessageDeletionRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val reactionDao: MessageReactionDao,
    private val outbox: DeletionOutbox,
) {

    /** Удалить сообщение только у себя (в любом чате). */
    suspend fun deleteForMe(chatId: String, messageId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val message = messageDao.getMessageById(messageId)
                ?: return@withContext Result.failure(IllegalStateException("Сообщение не найдено"))
            if (message.chatId != chatId) {
                return@withContext Result.failure(IllegalArgumentException("Сообщение из другого чата"))
            }
            deleteLocal(chatId, messageId)
            Result.success(Unit)
        }

    /**
     * Удалить своё сообщение У ВСЕХ (личный чат): конверт собеседнику и
     * стирание у себя. Собеседник недоступен - уходим с ошибкой, ничего
     * не стирая: половинное удаление хуже честного отказа.
     */
    suspend fun deleteForAllDirect(chatId: String, messageId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val message = messageDao.getMessageById(messageId)
                ?: return@withContext Result.failure(IllegalStateException("Сообщение не найдено"))
            if (message.chatId != chatId) {
                return@withContext Result.failure(IllegalArgumentException("Сообщение из другого чата"))
            }
            if (!message.isFromMe) {
                return@withContext Result.failure(SecurityException("Удалять у всех можно только своё сообщение"))
            }
            val peer = chatDao.getChatById(chatId)?.contactId?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(IllegalStateException("Собеседник не найден"))
            val envelope = "$PREFIX|$chatId|$messageId|${System.currentTimeMillis()}"
            // Раунд 137: команда уходит сразу и хранится в очереди до тех пор,
            // пока собеседник её не подтвердит (ack) - если его нет в сети,
            // помпа досылает, как только он появится. Половинных удалений
            // больше нет: у себя стираем сразу, у собеседника - гарантированно.
            val sent = RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, peer, envelope)
            outbox.add(
                DeletionOutbox.Entry(
                    targetId = messageId,
                    kind = DeletionOutbox.KIND_DIRECT,
                    chatId = chatId,
                    peerId = peer,
                    deleterId = "",
                    atMs = System.currentTimeMillis(),
                    lastTryMs = if (sent) System.currentTimeMillis() else 0L,
                    attempts = if (sent) 1 else 0,
                    tried = emptyList(),
                ),
            )
            deleteLocal(chatId, messageId)
            Result.success(Unit)
        }

    /**
     * Входящий конверт «удали у всех» в личном чате. Возвращает true, если
     * это конверт удаления (разобран или испорчен) - службу не сохранять
     * его как текст.
     */
    suspend fun routeIncoming(senderId: String, text: String): Boolean {
        if (!text.startsWith("$PREFIX|")) return false
        val parts = text.split('|')
        // Раунд 137: подтверждение доставки - отправитель снимает команду
        // с очереди и больше не досылает.
        if (parts.size == 3 && parts[1] == "ack") {
            val ackedId = parts[2]
            if (ackedId.isBlank()) return true
            withContext(Dispatchers.IO) { outbox.removeByTarget(DeletionOutbox.KIND_DIRECT, ackedId) }
            return true
        }
        if (parts.size != 4) {
            Log.w(TAG, "delete envelope from ${senderId.takeLast(8)} is malformed, dropped")
            return true
        }
        val targetId = parts[2]
        if (senderId.isBlank() || targetId.isBlank()) return true
        withContext(Dispatchers.IO) {
            // chatId в конверте - локальный чат ОТПРАВИТЕЛЯ (как у реакций):
            // свой чат находим по отправителю, а не по содержимому пакета.
            val chat = chatDao.getChatByContactId(senderId) ?: return@withContext
            val removed = messageDao.deleteByIdChatAndSender(targetId, chat.id, senderId)
            if (removed > 0) {
                reactionDao.deleteForMessage(targetId)
                refreshPreview(chat.id)
                Log.i(TAG, "remote delete applied id=$targetId from=${senderId.takeLast(8)}")
            }
            // Подтверждаем: отправитель перестанет досылать это удаление.
            runCatching {
                RustBridge.sendMessage(
                    UUID.randomUUID().toString(), chat.id, senderId,
                    "$PREFIX|ack|$targetId",
                )
            }
        }
        return true
    }

    /**
     * Раунд 137: досылка сохранённых команд «удалить у всех». Вызывается из
     * периодической помпы сервиса; одна попытка на запись раз в
     * [RETRY_INTERVAL_MS], записи старше [TTL_MS] сгорают. Запись direct
     * живёт до ack собеседника.
     */
    suspend fun pump() {
        val now = System.currentTimeMillis()
        val due = outbox.due(now, RETRY_INTERVAL_MS, TTL_MS)
            .filter { it.kind == DeletionOutbox.KIND_DIRECT }
        for (entry in due) {
            val chat = chatDao.getChatByContactId(entry.peerId)
            if (chat == null) {
                // Чат удалён - команде здесь больше некуда идти.
                outbox.remove(entry.kind, entry.chatId, entry.targetId)
                continue
            }
            val envelope = "$PREFIX|${chat.id}|${entry.targetId}|${entry.atMs}"
            val sent = runCatching {
                RustBridge.sendMessage(UUID.randomUUID().toString(), chat.id, entry.peerId, envelope)
            }.getOrDefault(false)
            outbox.markTried(entry, now)
            if (sent) Log.i(TAG, "delete re-sent id=${entry.targetId} to ${entry.peerId.takeLast(8)} (attempt ${entry.attempts + 1})")
        }
    }

    /** Стереть сообщение и его реакции, пересчитать превью чата. */
    private suspend fun deleteLocal(chatId: String, messageId: String) {
        // Куски длинного текста (InlineImage-хвосты) - долой вместе с головой:
        // иначе после удаления головы в ленте оставался бы обрывок.
        val stale = messageDao.getByContentPattern(chatId, InlineImage.textPartPattern(messageId))
            .filter { InlineImage.parseTextPart(it.content)?.headId == messageId }
        for (row in stale) messageDao.deleteById(row.id)
        messageDao.deleteById(messageId)
        reactionDao.deleteForMessage(messageId)
        refreshPreview(chatId)
    }

    /** Превью списка чатов - за оставшимся последним сообщением. */
    private suspend fun refreshPreview(chatId: String) {
        val last = messageDao.getLatest(chatId)
        if (last == null) {
            chatDao.updateLastMessage(chatId, "", 0L)
            return
        }
        chatDao.updateLastMessage(chatId, previewOf(last.content), last.timestamp)
    }

    /** Те же правила, что у GroupRepository.preview: без служебных строк. */
    private fun previewOf(content: String): String {
        if (com.vladimir.messenger.data.gif.GifLibrary.isGifRef(content)) {
            return "\ud83d\uddbc Гифка"
        }
        val clean = InlineImage.stripImage(content)
        val shown = if (clean.isBlank() && (InlineImage.hasImage(content) || InlineImage.photoCount(content) > 0)) {
            "Фото"
        } else {
            clean
        }
        return shown.replace('\n', ' ').take(PREVIEW_CHARS)
    }

    companion object {
        private const val TAG = "MsgDeletion"

        /** Маркер конверта «удали у всех» в личном чате. */
        const val PREFIX = "APUDEL1"

        /** Повторная досылка сохранённой команды - не чаще раза в столько. */
        const val RETRY_INTERVAL_MS = 10L * 60 * 1000

        /** Сколько хранить недоставленную команду (потом она сгорает). */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        private const val PREVIEW_CHARS = 80
    }
}
