package com.vladimir.messenger.ui.screens.groups

import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.group.MemberSummary
import com.vladimir.messenger.data.group.TopicSummary
import com.vladimir.messenger.data.local.entity.MessageEntity

/**
 * Состояние экрана группы/канала.
 *
 * Вынесено из `GroupChatViewModel.kt` отдельным файлом: файл упирается в
 * потолок god-файлов (`scripts/ci/check-god-files.py`), а состояние — шов без
 * логики: только данные для Compose.
 */
data class GroupChatUiState(
    val groupId: String = "",
    /** р237: черновик темы (текст поля ввода) - общий для устройств личности. */
    val draft: String = "",
    val group: GroupSummary? = null,
    /** Все свои группы и каналы — для левой колонки значков на экране чата. */
    val allGroups: List<GroupSummary> = emptyList(),
    val topics: List<TopicSummary> = emptyList(),
    val selectedTopicId: String? = null,
    val messages: List<MessageEntity> = emptyList(),
    val pinned: List<MessageEntity> = emptyList(),
    val members: List<MemberSummary> = emptyList(),
    val me: MemberSummary? = null,
    val canPin: Boolean = false,
    val canManageTopics: Boolean = false,
    /** Может ли этот пользователь модерировать чужие сообщения и участников (владелец или админ). */
    val canModerate: Boolean = false,
    /** Счётчики анти-рейтинга по узлам (`nodeId -> count`). */
    val antiRatings: Map<String, Int> = emptyMap(),
    /**
     * Узлы с активным временным предупреждением о всплеске жалоб
     * (`nodeId -> до какого времени`). Одиночные отметки 👎 сюда не попадают.
     */
    val antiWarnings: Map<String, Long> = emptyMap(),
    /** Узлы, которым этот телефон поставил анти-рейтинг. */
    val myAntiRatings: Set<String> = emptySet(),
    /**
     * Узлы-элита: участники, чей ранг VIP. У их имени в сообщении и у аватарки —
     * золотой знак и объёмное кольцо (решение владельца 2026-10-07: «VIP должно
     * быть видно везде, и в группах, и в каналах»).
     */
    val vipNodeIds: Set<String> = emptySet(),
    /** Выбранные сообщения в режиме множественного выбора. */
    val selectedMessageIds: Set<String> = emptySet(),
    /** р249: имена участников, которые сейчас печатают в этой теме. */
    val typingMembers: List<String> = emptyList(),
    /** Открытая карточка профиля участника в группе/канале. */
    val inspectedPeerId: String? = null,
    val inspectedPeerName: String = "",
    val inspectedPeerHearts: Int = 0,
    val inspectedPeerHeartMine: Boolean = false,
    val inspectedPeerAntiCount: Int = 0,
    val inspectedPeerAntiMine: Boolean = false,
    val inspectedPeerAntiWarning: Boolean = false,
    val inspectedPeerAntiUntilMs: Long = 0,
    val error: String? = null,
    val sending: Boolean = false,
    /** Открыты с темой из канала (комментарии): сразу лента, не список тем. */
    val startInTopic: Boolean = false,
    /** Реакции по сообщениям: ключ - id сообщения. */
    val reactions: Map<String, List<com.vladimir.messenger.data.reaction.ReactionSummary>> = emptyMap(),
    /** Счётчик репостов по сообщениям: ключ - id сообщения. */
    val repostCounts: Map<String, Int> = emptyMap(),
    /**
     * Сколько комментариев в этой ветке у сборщика (владельца канала) сверх
     * тех, что уже на телефоне: на большом канале комментарии приходят по
     * запросу, и лента показывает кнопку «Показать ещё N». 0 - нечего.
     */
    val moreComments: Int = 0,
    // ── Файлы группы (рой, этап 9) ──
    /** Передачи файлов этой группы (по хэшу файла карточка находит свою). */
    val transfers: List<com.vladimir.messenger.data.local.entity.FileTransferEntity> = emptyList(),
    /** Файлы, которые сейчас просим у сидов ([GroupFileMarker.key]). */
    val pendingFiles: Set<String> = emptySet(),
    /** Скольким участникам отдана моя общая копия файла (K2), по ключу файла. */
    val servedFiles: Map<String, Int> = emptyMap(),
    /** Идёт подготовка выбранного файла (хэш, копия). */
    val isPreparingFile: Boolean = false,
    /** Файл готов и ждёт отправки вместе с подписью: показывается над полем ввода. */
    val stagedFile: com.vladimir.messenger.util.GroupFileMarker.Info? = null,
    /** Можно ли прикреплять файлы: ранг «Круг друзей» и право «Отправка медиа». */
    val canAttach: Boolean = false,
    val attachLockedHint: String = "",
    /** Отдельное право на видео: «Проводник» (10), как и в личном чате. */
    val canSendVideo: Boolean = false,
    val videoLockedHint: String = "",
    /** Опросы открываются с «Круга друзей» (3); голосовать можно всегда. */
    val canCreatePoll: Boolean = false,
    val pollLockedHint: String = "",
    /** Каталог GIF (наш сервер): гифки, курсор «ещё», состояние. */
    val gifItems: List<com.vladimir.messenger.data.gif.GifItem> = emptyList(),
    val gifNext: String = "",
    val gifLoading: Boolean = false,
    /** Почему каталог недоступен (текст для диалога); null - доступен. */
    val gifError: String? = null,
    /** Раунд 192: мягкая плашка, когда показываем сохранённые результаты. */
    val gifNotice: String? = null,
    /** Раунд 121: свой каталог роя. tab: "swarm" | "external". */
    val gifTab: String = "swarm",
    val myGifs: List<com.vladimir.messenger.data.gif.GifLibEntry> = emptyList(),
    val swarmGifs: List<com.vladimir.messenger.data.gif.SwarmGif> = emptyList(),
    val swarmStatus: String? = null,
    /** Файл, который человек просит сохранить на телефон (системное окно). */
    val pendingSave: com.vladimir.messenger.data.file.PendingFileExport? = null,
    // ── р250: ответы на сообщения ──
    /** Сообщение, на которое отвечаем: цитата показывается над полем ввода. */
    val replyTo: MessageEntity? = null,
    // ── р250: опросы ──
    /** Опросы этой группы или канала: ключ - id сообщения (поста), под которым висит карточка. */
    val polls: Map<String, com.vladimir.messenger.data.group.PollSummary> = emptyMap(),
    // ── р250: медленный режим ──
    /** Сколько секунд участник ждёт между своими сообщениями; 0 - режим выключен. */
    val slowModeSeconds: Int = 0,
    /** Сколько миллисекунд осталось ждать; 0 - писать можно. */
    val slowModeWaitMs: Long = 0L,
    // ── Кнопка SOS ──
    /** Сколько миллисекунд ещё нельзя нажимать SOS; 0 - нажимать можно. */
    val sosCooldownMs: Long = 0L,
    /** Тревожные сообщения прямо сейчас уходят в группу. */
    val sosSending: Boolean = false,
)
