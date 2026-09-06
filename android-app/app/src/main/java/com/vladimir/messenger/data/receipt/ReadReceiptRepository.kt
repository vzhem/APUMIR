package com.vladimir.messenger.data.receipt

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.MessageDao
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Отчёты «прочитано»: синие галочки у отправителя.
 *
 * Доставленное и прочитанное раньше выглядели одинаково, и отправитель не
 * знал, увидел ли собеседник сообщение.
 *
 * Отчёт уходит, когда человек открыл переписку. Отдельного «прочитано» на
 * каждое сообщение нет: это лишний трафик, а смысл тот же.
 */
@Singleton
class ReadReceiptRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
) {
    /**
     * Сообщить собеседнику, что его сообщения прочитаны.
     *
     * Тихо выходит, если сообщать нечего или это групповой чат: там отчёт от
     * каждого участника превратился бы в лавину пакетов.
     */
    suspend fun reportRead(chatId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val chat = chatDao.getChatById(chatId) ?: return@runCatching
                val peer = chat.contactId
                if (peer.isBlank() || !peer.startsWith("pk_")) return@runCatching

                val incoming = messageDao.recentIncoming(chatId, ReadReceiptWire.MAX_IDS)
                if (incoming.isEmpty()) return@runCatching

                // Отправителю нужен ЕГО идентификатор чата: у него переписка
                // заведена под своим. В личном чате это адрес узла - он же
                // contactId с нашей стороны.
                val myNodeId = RustBridge.nodeId().orEmpty()
                if (myNodeId.isBlank()) return@runCatching

                val envelope = ReadReceiptWire.build(
                    chatId = myNodeId,
                    messageIds = incoming.map { it.id },
                    atMs = System.currentTimeMillis(),
                ) ?: return@runCatching

                RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, peer, envelope)
                Log.i(TAG, "read receipt sent for ${incoming.size} message(s)")
            }.onFailure { Log.w(TAG, "read receipt failed: ${it.message}") }
        }
    }

    /**
     * Входящий отчёт: помечаем свои сообщения прочитанными.
     *
     * @return true, если это был отчёт - тогда служба не сохраняет его текстом.
     */
    suspend fun routeIncoming(senderId: String, text: String): Boolean {
        if (!ReadReceiptWire.isReadReceipt(text)) return false
        val packet = ReadReceiptWire.parse(text)
        if (packet == null) {
            Log.w(TAG, "read receipt from $senderId is malformed, dropped")
            return true
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val updated = messageDao.markReadByIds(packet.messageIds)
                Log.i(TAG, "read receipt from $senderId marked $updated message(s)")
            }.onFailure { Log.w(TAG, "read receipt apply failed: ${it.message}") }
        }
        return true
    }

    private companion object {
        const val TAG = "ReadReceipt"
    }
}
