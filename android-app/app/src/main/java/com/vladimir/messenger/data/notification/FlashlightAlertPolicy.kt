package com.vladimir.messenger.data.notification

/**
 * Режим вспышки фонарика для обычных входящих сообщений.
 *
 * Выбирается локально на телефоне получателя: от полного отключения до
 * всех сообщений или одного выбранного раздела (личные чаты, группы, каналы).
 */
enum class FlashlightMessageMode(val id: String) {
    OFF("off"),
    ALL("all"),
    PERSONAL("personal"),
    GROUPS("groups"),
    CHANNELS("channels");

    companion object {
        fun fromId(raw: String?): FlashlightMessageMode =
            entries.firstOrNull { it.id == raw } ?: OFF
    }
}

/** Раздел, из которого пришло обычное входящее сообщение. */
enum class FlashlightMessageKind {
    PERSONAL,
    GROUP,
    CHANNEL;

    companion object {
        fun classify(hasGroup: Boolean, isChannel: Boolean): FlashlightMessageKind = when {
            !hasGroup -> PERSONAL
            isChannel -> CHANNEL
            else -> GROUP
        }
    }
}

/** Локальные настройки мигания фонарика у получателя. */
data class FlashlightAlertConfig(
    val sosEnabled: Boolean = FlashlightAlertPolicy.DEFAULT_SOS_ENABLED,
    val messageMode: FlashlightMessageMode = FlashlightAlertPolicy.DEFAULT_MESSAGE_MODE,
)

/** Какая серия вспышек сейчас выполняется на фонарике. */
enum class ActiveBlinkKind {
    SOS,
    MESSAGE,
}

/** Снимок текущей активной серии вспышек (время в монотонных мс). */
data class ActiveBlinkState(
    val kind: ActiveBlinkKind,
    val startedAtMs: Long,
)

/** Решение планировщика вспышки без привязки к Android SDK. */
enum class BlinkDecision {
    /** Мигание выключено настройкой или подавлено тихим/беззвучным режимом. */
    NONE,

    /** Уже идёт подходящая серия вспышек (пачка SOS или частые сообщения). */
    COALESCED,

    /** Начать тревожную серию SOS (при необходимости прервав обычную). */
    START_SOS,

    /** Начать короткую серию для обычного сообщения. */
    START_MESSAGE,
}

/**
 * Чистые правила мигания фонарика для тревоги SOS и входящих сообщений.
 *
 * Почему правила вынесены без Android:
 * - одно нажатие красной кнопки SOS в группе шлёт от 3 до 5 сообщений подряд;
 *   они не должны драться за фонарик и обрывать друг друга на полуслове;
 * - тревога SOS работает даже в группе без звука, но получатель вправе явно
 *   выключить мигание фонарика для SOS у себя в настройках;
 * - обычные сообщения подчиняются выбранному режиму (все / только личные /
 *   группы / каналы), тихим сообщениям и паузам звука.
 */
object FlashlightAlertPolicy {
    const val DEFAULT_SOS_ENABLED: Boolean = true
    val DEFAULT_MESSAGE_MODE: FlashlightMessageMode = FlashlightMessageMode.OFF

    /**
     * Окно склейки пачки SOS (3–5 сообщений за одно нажатие кнопки): меньше
     * защитной паузы кнопки GroupSos.COOLDOWN_MS (10 с), чтобы следующее
     * настоящее нажатие снова дало полную серию вспышек.
     */
    const val SOS_COALESCE_WINDOW_MS: Long = 4_000L

    /** Защита от стробоскопа при потоке обычных сообщений подряд. */
    const val MESSAGE_COOLDOWN_MS: Long = 1_500L

    /** Верхние границы удержания PARTIAL_WAKE_LOCK на время серии вспышек. */
    const val SOS_WAKE_LOCK_MS: Long = 6_000L
    const val MESSAGE_WAKE_LOCK_MS: Long = 2_000L

    /**
     * Рисунок SOS по Морзе (··· −−− ···): чётные индексы — фонарик горит,
     * нечётные — пауза. Три коротких, три длинных, три коротких (~3,5 с).
     */
    val SOS_PATTERN_MS: LongArray = longArrayOf(
        150L, 120L, 150L, 120L, 150L,
        280L,
        420L, 150L, 420L, 150L, 420L,
        280L,
        150L, 120L, 150L, 120L, 150L,
    )

    /** Короткая двойная вспышка на обычное сообщение (~0,4 с). */
    val MESSAGE_PATTERN_MS: LongArray = longArrayOf(140L, 120L, 140L)

    fun shouldBlinkForSos(config: FlashlightAlertConfig, isGroup: Boolean): Boolean =
        isGroup && config.sosEnabled

    fun shouldBlinkForMessage(
        config: FlashlightAlertConfig,
        kind: FlashlightMessageKind,
        silent: Boolean,
        muted: Boolean,
    ): Boolean {
        if (silent || muted) return false
        return when (config.messageMode) {
            FlashlightMessageMode.OFF -> false
            FlashlightMessageMode.ALL -> true
            FlashlightMessageMode.PERSONAL -> kind == FlashlightMessageKind.PERSONAL
            FlashlightMessageMode.GROUPS -> kind == FlashlightMessageKind.GROUP
            FlashlightMessageMode.CHANNELS -> kind == FlashlightMessageKind.CHANNEL
        }
    }

    fun decideSos(
        config: FlashlightAlertConfig,
        isGroup: Boolean,
        active: ActiveBlinkState?,
        nowMs: Long,
    ): BlinkDecision {
        if (!shouldBlinkForSos(config, isGroup)) return BlinkDecision.NONE
        if (
            active != null &&
            active.kind == ActiveBlinkKind.SOS &&
            nowMs >= active.startedAtMs &&
            nowMs - active.startedAtMs < SOS_COALESCE_WINDOW_MS
        ) {
            return BlinkDecision.COALESCED
        }
        return BlinkDecision.START_SOS
    }

    fun decideMessage(
        config: FlashlightAlertConfig,
        kind: FlashlightMessageKind,
        silent: Boolean,
        muted: Boolean,
        active: ActiveBlinkState?,
        nowMs: Long,
    ): BlinkDecision {
        if (!shouldBlinkForMessage(config, kind, silent, muted)) return BlinkDecision.NONE
        if (active != null && nowMs >= active.startedAtMs) {
            val elapsed = nowMs - active.startedAtMs
            val window = when (active.kind) {
                ActiveBlinkKind.SOS -> SOS_COALESCE_WINDOW_MS
                ActiveBlinkKind.MESSAGE -> MESSAGE_COOLDOWN_MS
            }
            if (elapsed < window) return BlinkDecision.COALESCED
        }
        return BlinkDecision.START_MESSAGE
    }
}
