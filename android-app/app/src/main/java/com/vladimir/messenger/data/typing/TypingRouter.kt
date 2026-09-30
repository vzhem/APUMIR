package com.vladimir.messenger.data.typing

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.mirror.MirrorHub
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * р235: отправка и приём пакетов «печатает…».
 *
 * Отправляет то же устройство, которое ведёт сеть; у тени сессии нет, поэтому
 * она отдаёт пакет активному через зеркало - ровно как чтения и реакции
 * (р228). Второе устройство той же личности тоже получает сигнал: индикатор
 * должен быть на обоих телефонах, иначе они «отличаются».
 *
 * Частоту держит вызывающий (см. ChatDetailViewModel): пакет уходит не чаще
 * раза в ~2.5 с, а не на каждую букву.
 */
object TypingRouter {

    private const val TAG = "TypingRouter"

    /**
     * Своё «печатает…» собеседнику. Быстро и без базы - это мимолётный сигнал.
     * Вызывается с главного потока (набор текста), а отправка ядра бывает
     * блокирующей, поэтому работа уходит в IO-поток.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun publishLocal(peerId: String, chatId: String, typing: Boolean) {
        if (peerId.isBlank() || !peerId.startsWith("pk_")) return
        scope.launch { publishNow(peerId, chatId, typing) }
    }

    private suspend fun publishNow(peerId: String, chatId: String, typing: Boolean) {
        val envelope = TypingWire.build(typing)
        try {
            // Тень: пакет несёт активный партнёр (своей сессии у неё нет).
            if (MirrorHub.deliverAction(peerId = peerId, groupId = "", chatId = chatId, text = envelope)) {
                Log.d(TAG, "typing via mirror partner: $typing")
            } else {
                // р239: «печатает…» - мимолётный сигнал, надёжной очереди он не
                // нужен: сначала лучший канал (прямой QUIC, без хранения и
                // повторов), и только если он недоступен - обычная отправка.
                // Так поток «печатает» не занимает очередь сообщений.
                val direct = runCatching { RustBridge.sendDirectPayload(peerId, envelope) }
                    .getOrDefault(false)
                if (!direct) {
                    RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, peerId, envelope)
                }
                // Своё действие активного - и партнёрскому устройству личности,
                // чтобы индикатор был на обоих телефонах.
                MirrorHub.publishOwnAction(peerId = peerId, groupId = "", chatId = chatId, text = envelope)
            }
        } catch (e: Exception) {
            Log.d(TAG, "typing publish skipped: ${e.message}")
        }
    }

    /**
     * Входящий пакет. Разбирается до сохранения в чат: в переписке мусора нет.
     * Возврат true - пакет наш, дальше по цепочке его пускать не нужно.
     */
    fun routeIncoming(senderId: String, text: String): Boolean {
        val typing = TypingWire.parse(text) ?: return false
        if (typing) TypingPeer.peerTyping(senderId) else TypingPeer.peerStopped(senderId)
        // р235: индикатор нужен на ОБОИХ устройствах личности: у второго
        // своей сессии нет, поэтому кадр зеркала несёт то устройство,
        // которое получило пакет из сети.
        MirrorHub.publishTyping(senderId, typing)
        return true
    }
}
