package com.vladimir.messenger.data.repository

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.mirror.MirrorHub
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Редактирование СВОЕГО уже отправленного сообщения в личном чате —
 * в том числе прочитанного (просьба владельца: в меню сообщения не было
 * пункта «Редактировать»).
 *
 * Как устроено (по образцу «удалить у всех» APUDEL1):
 *  - у себя текст меняется сразу, строка получает отметку editedAtMs
 *    (пузырь показывает «изменено»);
 *  - собеседнику уходит служебный конверт APUEDIT1 обычным надёжным путём
 *    ядра: если его нет в сети, ядро доставит при появлении, как сообщение;
 *  - получатель применяет правку только к сообщению этого отправителя,
 *    только если правка свежее уже применённой (метка atMs), и отвечает
 *    ack — повторные конверты вреда не делают;
 *  - второму устройству той же личности правка уходит зеркальным кадром
 *    «своё действие», чтобы оба телефона показывали один текст.
 *
 * Формат: `APUEDIT1|<chatId отправителя>|<messageId>|<atMs>|<текст base64url>`;
 * подтверждение: `APUEDIT1|ack|<messageId>`. Текст в base64: в нём могут
 * быть переводы строк и `|`.
 */
@Singleton
class MessageEditRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
) {

    /** Отредактировать своё сообщение в личном чате. */
    suspend fun editDirect(chatId: String, messageId: String, newText: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val text = newText.trim()
            if (text.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Пустое сообщение"))
            }
            if (text.length > MAX_EDIT_CHARS) {
                return@withContext Result.failure(
                    IllegalArgumentException("Сообщение длиннее $MAX_EDIT_CHARS символов"),
                )
            }
            val message = messageDao.getMessageById(messageId)
                ?: return@withContext Result.failure(IllegalStateException("Сообщение не найдено"))
            if (message.chatId != chatId) {
                return@withContext Result.failure(IllegalArgumentException("Сообщение из другого чата"))
            }
            if (!message.isFromMe) {
                return@withContext Result.failure(SecurityException("Править можно только своё сообщение"))
            }
            val peer = chatDao.getChatById(chatId)?.contactId?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(IllegalStateException("Собеседник не найден"))
            if (message.content == text) return@withContext Result.success(Unit)

            val atMs = System.currentTimeMillis()
            applyLocal(chatId, messageId, text, atMs)

            val envelope = buildEnvelope(chatId, messageId, atMs, text)
            // Как и у удаления: зеркало-телефон без сессии отдаёт конверт
            // активному партнёру, а партнёр применяет правку и у себя.
            val viaMirror = MirrorHub.deliverAction(
                peerId = peer, groupId = "", chatId = chatId, text = envelope,
            )
            if (!viaMirror) {
                runCatching {
                    RustBridge.sendMessage(editWireId(peer, messageId, atMs), chatId, peer, envelope)
                }
                MirrorHub.publishOwnAction(
                    peerId = peer, groupId = "", chatId = chatId, text = envelope,
                )
            }
            Log.i(TAG, "message edited id=$messageId at=$atMs viaMirror=$viaMirror")
            Result.success(Unit)
        }

    /**
     * Входящий конверт правки. true — пакет наш (применён, устарел или
     * битый): в переписку ему дороги нет в любом случае.
     */
    suspend fun routeIncoming(senderId: String, text: String): Boolean {
        if (!text.startsWith("$PREFIX|")) return false
        val parts = text.split("|")
        if (parts.size == 3 && parts[1] == "ack") return true // подтверждение: очередь у нас нет, просто съедаем
        if (parts.size != 5) {
            Log.w(TAG, "edit envelope malformed, dropped")
            return true
        }
        val targetId = parts[2]
        val atMs = parts[3].toLongOrNull() ?: return true
        val newText = decodeText(parts[4]) ?: return true
        if (senderId.isBlank() || targetId.isBlank() || newText.isBlank()) return true
        withContext(Dispatchers.IO) {
            val chat = chatDao.getChatByContactId(senderId) ?: return@withContext
            val message = messageDao.getMessageById(targetId) ?: return@withContext
            // Правит только автор и только в этой переписке.
            if (message.chatId != chat.id || message.senderId != senderId) return@withContext
            if (applyLocal(chat.id, targetId, newText, atMs)) {
                Log.i(TAG, "remote edit applied id=$targetId")
                runCatching {
                    RustBridge.sendMessage(
                        editWireId(senderId, targetId, atMs), chat.id, senderId,
                        "$PREFIX|ack|$targetId",
                    )
                }
            }
        }
        return true
    }

    /**
     * Правка сделана на партнёрском устройстве личности: применяем тот же
     * текст у себя. Отправитель здесь — собеседник по переписке (peerId
     * кадра), а сообщение — своё для владельца обоих устройств.
     */
    suspend fun applyMirrorOutgoing(peerId: String, text: String): Boolean {
        if (!text.startsWith("$PREFIX|")) return false
        val parts = text.split("|")
        if (parts.size != 5) return true
        val targetId = parts[2]
        val atMs = parts[3].toLongOrNull() ?: return true
        val newText = decodeText(parts[4]) ?: return true
        if (peerId.isBlank() || targetId.isBlank()) return true
        withContext(Dispatchers.IO) {
            val chat = chatDao.getChatByContactId(peerId) ?: return@withContext
            val message = messageDao.getMessageById(targetId) ?: return@withContext
            if (message.chatId != chat.id) return@withContext
            applyLocal(chat.id, targetId, newText, atMs)
        }
        return true
    }

    /** Применить текст и отметку; false — правка не свежее уже известной. */
    private suspend fun applyLocal(chatId: String, messageId: String, newText: String, atMs: Long): Boolean {
        val current = messageDao.getMessageById(messageId) ?: return false
        if (atMs <= current.editedAtMs) return false
        messageDao.updateContentEdited(messageId, newText, atMs)
        refreshPreview(chatId)
        return true
    }

    /** Предпросмотр в списке чатов — по последнему сообщению, как после удаления. */
    private suspend fun refreshPreview(chatId: String) {
        val last = messageDao.getLatest(chatId)
        if (last == null) {
            chatDao.updateLastMessage(chatId, "", 0L)
            return
        }
        chatDao.updateLastMessage(
            chatId,
            com.vladimir.messenger.util.ChatPreviews.human(last.content) ?: last.content,
            last.timestamp,
        )
    }

    private fun buildEnvelope(chatId: String, messageId: String, atMs: Long, text: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.toByteArray(Charsets.UTF_8))
        return "$PREFIX|$chatId|$messageId|$atMs|$encoded"
    }

    private fun decodeText(encoded: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8).trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Устойчивый id конверта в ядре: повторная досылка не заводит вторую запись. */
    private fun editWireId(peer: String, messageId: String, atMs: Long): String =
        UUID.nameUUIDFromBytes("apu-edit|$peer|$messageId|$atMs".toByteArray(Charsets.UTF_8)).toString()

    companion object {
        const val PREFIX = "APUEDIT1"
        const val MAX_EDIT_CHARS = 8_000
        private const val TAG = "MessageEdit"
    }
}
