package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.domain.model.Message

/**
 * Состояние экрана личного чата.
 *
 * Вынесено из `ChatDetailViewModel.kt` отдельным файлом: файл упирается в
 * потолок god-файлов (`scripts/ci/check-god-files.py`), а состояние — ровно
 * тот шов, который не зависит от логики: только данные для Compose.
 */
data class ChatDetailUiState(
    val messages: List<Message> = emptyList(),
    /** Раунд 173: закреплённые сообщения чата (свежие вверху). */
    val pinned: List<Message> = emptyList(),
    /** р235: собеседник сейчас печатает («печатает…» в шапке чата). */
    val isPeerTyping: Boolean = false,
    /**
     * р241: переписка с СОБСТВЕННЫМ узлом (в контакты попал свой адрес -
     * например, отсканировали собственный QR из профиля). Такая переписка
     * никуда не ведёт: собеседника в ней нет. Показываем честную плашку,
     * вместо того чтобы молча копить строки «в ожидании».
     */
    val isSelfChat: Boolean = false,
    /**
     * Собеседник сообщил ранг VIP (APURANK1) — у имени в шапке чата появляется
     * знак, а вокруг аватарки — объёмное золотое кольцо с блеском.
     */
    val peerVip: Boolean = false,
    /** Что известно о ранге собеседника (null - ещё не сообщал). */
    val peerRankQualified: Int? = null,
    /**
     * От собеседника приходят конверты, которые не вскрываются: у него осталась
     * прежняя копия нашего ключа (переустановка/восстановление профиля). Пока
     * это так, в переписке висит золотая плашка с объяснением и кнопкой
     * «Отправить мой ключ» — раньше об этом знал только отчёт «Логи».
     */
    val keyDesync: Boolean = false,
    /**
     * Переписка с САМИМ СОБОЙ и мой ранг — VIP: тогда кольцо и знак показываем
     * у своего узла (в такой переписке собеседника нет).
     */
    val selfVip: Boolean = false,
    val transfers: List<FileTransferEntity> = emptyList(),
    val inputText: String       = "",
    val isLoading: Boolean      = true,
    /** Local database error, distinct from sending/network errors. */
    val historyError: String?   = null,
    val isSending: Boolean      = false,
    val isPreparingFile: Boolean = false,
    val error: String?          = null,
    val isContactOnline: Boolean = false,
    /** Срок паузы уведомлений этого личного чата. */
    val mutedUntilMs: Long = 0L,
    /** @никнейм собеседника: показывает карточка профиля. */
    val contactUsername: String = "",
    /** Сердечки и анти-рейтинг профиля собеседника. */
    val heartCount: Int = 0,
    val heartMine: Boolean = false,
    val antiRatingCount: Int = 0,
    val antiRatingMine: Boolean = false,
    /** Временное предупреждение о всплеске жалоб (не одиночные отметки). */
    val antiRatingWarning: Boolean = false,
    val antiRatingUntilMs: Long = 0,
    val scrollToBottom: Boolean = false,
    val pendingSave: com.vladimir.messenger.data.file.PendingFileExport? = null,
    /** Non-null asks the screen to open Android's system picker for an explicit file retry. */
    val pendingRetryFilePickerId: String? = null,
    /** Ранг ещё не открыл вложения: кнопка объяснит это сразу, а не после выбора файла. */
    val canSendAttachments: Boolean = true,
    val attachmentsLockedHint: String = "",
    /** Отправка только фото/файлов/голоса/GIF/стикеров, видео ещё закрыто («Проводник», 10). */
    val canSendVideo: Boolean = true,
    val videoLockedHint: String = "",
    /** р252: опросы открываются с «Круга друзей» (3); голосовать можно всегда. */
    val canCreatePoll: Boolean = true,
    val pollLockedHint: String = "",
    /** Карточки опросов этого чата: ключ - id сообщения с вопросом. */
    val polls: Map<String, com.vladimir.messenger.data.group.PollSummary> = emptyMap(),
    /** Звонки и видеозвонки открываются с «Круга друзей» (3). */
    val canCall: Boolean = true,
    val canVideoCall: Boolean = true,
    val callLockedHint: String = "",
    /** Каталог GIF (наш сервер): гифки, курсор «ещё», состояние. */
    val gifItems: List<com.vladimir.messenger.data.gif.GifItem> = emptyList(),
    val gifNext: String = "",
    val gifLoading: Boolean = false,
    val gifError: String? = null,
    /** Раунд 192: мягкая плашка, когда показываем сохранённые результаты. */
    val gifNotice: String? = null,
    /** Раунд 121: свой каталог роя. tab: "swarm" | "external". */
    val gifTab: String = "swarm",
    val myGifs: List<com.vladimir.messenger.data.gif.GifLibEntry> = emptyList(),
    val swarmGifs: List<com.vladimir.messenger.data.gif.SwarmGif> = emptyList(),
    val swarmStatus: String? = null,
    /** Реакции по сообщениям: ключ - id сообщения. */
    val reactions: Map<String, List<com.vladimir.messenger.data.reaction.ReactionSummary>> = emptyMap(),
    /** Счётчик репостов по сообщениям: ключ - id сообщения. */
    val repostCounts: Map<String, Int> = emptyMap(),
    val replyTo: Message? = null,
)
