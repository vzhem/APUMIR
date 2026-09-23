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
            val sent = RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, peer, envelope)
            if (!sent) {
                return@withContext Result.failure(IllegalStateException("Собеседник недоступен - попробуйте позже"))
            }
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
        }
        return true
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

        private const val PREVIEW_CHARS = 80
    }
}
