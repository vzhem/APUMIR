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
