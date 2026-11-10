package com.vladimir.messenger.data.file

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.chatclear.ChatClearDelivery
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.repository.ChatRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/** Applies paired-device file metadata with clear-tombstone checks on both sides of insertion. */
@Singleton
class MirrorFileMetaApplier @Inject constructor(
    private val chatRepository: ChatRepository,
    private val transferDao: FileTransferDao,
    private val chatClear: ChatClearDelivery,
) {
    companion object { private const val TAG = "MirrorFileMeta" }
    suspend fun apply(meta: org.json.JSONObject, notify: Boolean): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
        val transferId = meta.optString("id")
        val peer = meta.optString("peer")
        val groupId = meta.optString("g")
        if (transferId.isBlank()) return@withContext false
        if (groupId.isBlank() && peer.isBlank()) return@withContext false
        // р249: автор файла в группе (в личном чате это и есть `peer`).
        val peerForRow = if (groupId.isNotBlank()) meta.optString("from") else peer
        // р249: в группе/канале чат один и тот же на всех устройствах (его id
        // совпадает), поэтому партнёр кладёт передачу сразу в него; в личном
        // чате номер у каждого свой - ищем по узлу собеседника.
        val chatId = if (groupId.isNotBlank()) {
            groupId
        } else {
            val chat = chatRepository.getChatByContactId(peer)
                ?: chatRepository.getOrCreateChat(peer, com.vladimir.messenger.util.NodeIds.autoName(peer))
            chat.id
        }
        val messageId = meta.optString("msg").ifBlank { FileTransferWire.chatPlaceholderMessageId(transferId) }
        val name = meta.optString("name")
        if (chatClear.suppressMirrorFile(peer, groupId, name, messageId, transferId)) return@withContext true
        val mime = meta.optString("mime")
        val size = meta.optLong("size", 0L)
        val sha = meta.optString("sha")
        val chunks = meta.optLong("chunks", 1L)
        val chunkSize = meta.optInt("chunk", 64 * 1024)
        val timestamp = meta.optLong("ts", System.currentTimeMillis())
        val outgoing = meta.optString("dir") == "out"
        val existing = transferDao.getTransfer(transferId)
        if (existing == null) {
            transferDao.insertTransferIgnore(
                com.vladimir.messenger.data.local.entity.FileTransferEntity(
                    transferId = transferId,
                    messageId = messageId,
                    chatId = chatId,
                    peerNodeId = peerForRow,
                    direction = if (outgoing) "OUTGOING" else "INCOMING",
                    displayName = name,
                    mediaType = mime,
                    totalBytes = size,
                    chunkSize = chunkSize,
                    chunkCount = chunks,
                    fileSha256 = sha,
                    state = "COMPLETE",
                    completedChunks = chunks,
                    transferredBytes = 0,
                    createdAtMs = timestamp,
                    // Владелец 2026-10-07: тяжёлое (фото, видео, большие файлы)
                    // хранится сутки, текст и малое — неделю.
                    expiresAtMs = timestamp +
                        FileTransferRetention.ttlMs(mime, size),
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
        // р249: в группе карточку приносит само сообщение группы (визитка
        // GroupFileMarker) - оно и так доезжает по зеркалу групповым конвертом.
        // Свою строку здесь заводить нельзя: без темы она не попала бы ни в
        // одну тему и превратилась бы в дубль.
        val groupScope = groupId.isNotBlank()
        if (!groupScope && chatRepository.messageExists(messageId) != true) {
            val content = meta.optString("text").ifBlank { FileTransferRouter.formatPlaceholder(name, mime, size) }
            if (outgoing) {
                chatRepository.insertLocalFileMessage(
                    chatId = chatId,
                    recipientId = peerForRow,
                    messageId = messageId,
                    content = content,
                    timestamp = timestamp,
                )
            } else {
                chatRepository.saveIncomingMessage(
                    chatId = chatId,
                    senderId = peerForRow,
                    messageId = messageId,
                    content = content,
                    timestamp = timestamp,
                    recipientId = RustBridge.nodeId() ?: "",
                    notifyArrival = notify,
                )
            }
        }
        if (chatClear.suppressMirrorFile(peer, groupId, name, messageId, transferId)) return@withContext true
        Log.i(
            TAG,
            "mirror file meta applied id=$transferId ($name, $size B, out=$outgoing" +
                (if (groupScope) ", группа" else "") + ")",
        )
        true
    }

}
