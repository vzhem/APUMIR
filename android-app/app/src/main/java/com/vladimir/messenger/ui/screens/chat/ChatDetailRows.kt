package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.domain.model.Message

/**
 * Строка списка сообщений: сообщение и его файловая передача приходят из двух
 * разных таблиц, а показывать их нужно одной лентой.
 *
 * Вынесено из `ChatDetailScreen.kt` отдельным файлом: экран уже упирается в
 * потолок god-файлов (`scripts/ci/check-god-files.py`), и новое в нём
 * появляется только выносом по шву. Данные строки — ровно такой шов: они не
 * рисуют ничего и нужны только экрану.
 */
internal data class ChatRow(
    val key: String,
    val message: Message?,
    val transfer: FileTransferEntity?,
    val orderMs: Long,
)

/** Что откатывать, если «сбросить оформление этого чата» нажали случайно. */
internal data class LocalSettingsUndo(
    val scope: String,
    val wallpaper: String?,
    val textColor: String?,
)

/** Build the feed away from the UI thread; file-transfer history can be large. */
internal fun buildChatRows(messages: List<Message>, transfers: List<FileTransferEntity>): List<ChatRow> {
    val transfersByMessageId = transfers.associateBy { it.messageId }
    val knownMessageIds = messages.mapTo(HashSet()) { it.id }
    val shadowed = transfers
        .filter { it.direction == "INCOMING" && it.state != "COMPLETE" }
        .groupBy { it.fileSha256 }
        .flatMap { (_, group) -> group.sortedBy { it.createdAtMs }.drop(1).map { it.transferId } }
        .toSet()
    val rows = messages.map { message ->
        ChatRow(message.id, message, transfersByMessageId[message.id], message.timestamp)
    } + transfers.filter {
        it.direction == "INCOMING" && it.state != "COMPLETE" &&
            it.messageId !in knownMessageIds && it.transferId !in shadowed &&
            !it.mediaType.equals("image/gif", ignoreCase = true) &&
            !it.displayName.startsWith(com.vladimir.messenger.data.file.STICKER_FILE_PREFIX)
    }.map { transfer ->
        ChatRow("t-${transfer.transferId}", null, transfer, transfer.createdAtMs)
    }
    return rows.sortedBy { it.orderMs }
}

internal data class ChatRowsSnapshot(
    val messages: List<Message>,
    val transfers: List<FileTransferEntity>,
    val rows: List<ChatRow>,
)

/** Keep the last snapshot during refresh; a different chat starts with an empty one. */
@androidx.compose.runtime.Composable
internal fun rememberChatRows(
    chatId: String,
    messages: List<Message>,
    transfers: List<FileTransferEntity>,
): ChatRowsSnapshot? {
    val snapshot = androidx.compose.runtime.remember(chatId) {
        androidx.compose.runtime.mutableStateOf<ChatRowsSnapshot?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(chatId, messages, transfers) {
        val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildChatRows(messages, transfers)
        }
        snapshot.value = ChatRowsSnapshot(messages, transfers, rows)
    }
    return snapshot.value
}
