package com.vladimir.messenger.data.rank

import com.vladimir.messenger.data.file.FileTransferRankPolicy

/**
 * Возможности приложения и ранг, с которого каждая открывается.
 *
 * До третьего ранга («Круг друзей») приложение работает в минимальном режиме:
 * текстовые сообщения, приём и просмотр присланных файлов, вступление в
 * сообщества, игра «Сердечки», обмен контактами. Всё, что требует отправки
 * чего-то своего наружу (фото, голос, GIF, стикеры, документы, геолокация,
 * истории, опросы, звонки), открывается с третьего ранга; видео, создание
 * групп и каналов и автосбор прокси — с десятого. Решение владельца:
 * с десятого ранга («Проводник») разрешено ВСЁ — выше него новых возможностей
 * нет, растут только статус, знак и VIP-оформление (решения владельца от
 * 2026-08-27 и 2026-11-03).
 *
 * Почему отдельный объект, а не «по месту»: правил стало больше одного
 * экрана, и каждое новое ограничение, дописанное прямо в экран, расходится с
 * остальными. Здесь один список порогов, из него читают интерфейс,
 * ViewModel-и, сервисы и экран «Ранги и возможности».
 *
 * ВАЖНО: приём медиа, текст, вступление в сообщества, игра сердечек и
 * обновления не ограничиваются НИКОГДА — иначе ранг нельзя ни заработать, ни
 * обойти нечестно (см. docs/MASTER_PLAN_v2.md, «Что доступно сразу после
 * установки»).
 */
enum class RankCapability(val minimumQualifiedReferrals: Int) {
    /** Текстовые сообщения в личных чатах и сообществах. */
    SEND_TEXT(0),

    /** Приём, скачивание и просмотр присланных фото, видео и файлов. */
    RECEIVE_MEDIA(0),

    /** Вступление в группы и каналы по приглашению и обычное участие. */
    JOIN_COMMUNITIES(0),

    /** Игра «Сердечки»: единственный способ заработать ранг самому. */
    HEART_GAME(0),

    /** Отправка контакта другому человеку. */
    SHARE_CONTACT(0),

    /** Ручное добавление прокси (диагностическое действие). */
    PROXY_MANUAL(1),

    /** Отправка фото из галереи, камеры и вставкой из буфера. */
    SEND_PHOTO(3),

    /** Голосовые сообщения, в том числе с эффектами голоса. */
    SEND_VOICE(3),

    /** Отправка GIF из каталога. */
    SEND_GIF(3),

    /** Отправка стикера (стикер-клавиатура и каталог). */
    SEND_STICKER(3),

    /** Отправка произвольного документа. Размер файла рангом не ограничен. */
    SEND_DOCUMENT(3),

    /** «Поделиться координатами» на карте. */
    SHARE_LOCATION(3),

    /** Публикация своей истории. Просмотр чужих историй бесплатен. */
    PUBLISH_STORY(3),

    /** Исходящий голосовой звонок 1:1. */
    CALL_VOICE(3),

    /** Исходящий видеозвонок 1:1. */
    CALL_VIDEO(3),

    /** Создание опроса в чате или группе. Голосование бесплатно. */
    CREATE_POLL(3),

    /** Отправка видео: самый тяжёлый для роя тип файла. */
    SEND_VIDEO(10),

    /** Создание группы. */
    CREATE_GROUP(10),

    /** Автосбор, проверка и автоматический выбор прокси. */
    PROXY_AUTOMATIC(10),

    /** Создание канала: с того же «Проводника», что и группы. */
    CREATE_CHANNEL(10),
}

object FeatureRankPolicy {

    /** Ранг 0 — «Гость»: свежая установка, ничего не заработано. */
    const val BASIC_RANK = 0

    /** Ранг 3 — «Круг друзей»: минимальный набор отправки. */
    const val CIRCLE_RANK = 3

    /** Ранг 10 — «Проводник»: первая ступень VIP. */
    const val CONDUCTOR_RANK = 10

    /**
     * Ранг полного доступа: с «Проводника» (10) открыта каждая возможность
     * из [RankCapability]. Ни один порог не вправе быть выше этого числа —
     * ступени 20–1000 дают только статус и оформление.
     */
    const val FULL_ACCESS_RANK = 10

    /** Сколько подтверждённых приглашений нужно для возможности. */
    fun requiredReferrals(capability: RankCapability): Int = capability.minimumQualifiedReferrals

    /** Открыта ли возможность на этом ранге. */
    fun isAllowed(qualifiedDirectReferrals: Int, capability: RankCapability): Boolean =
        qualifiedDirectReferrals.coerceAtLeast(0) >= capability.minimumQualifiedReferrals

    /** Все возможности, открытые на этом ранге. */
    fun unlockedFeatures(qualifiedDirectReferrals: Int): List<RankCapability> =
        RankCapability.entries.filter { isAllowed(qualifiedDirectReferrals, it) }

    /** Открыто ли всё без остатка: с ранга полного доступа — да. */
    fun isEverythingAllowed(qualifiedDirectReferrals: Int): Boolean =
        qualifiedDirectReferrals.coerceAtLeast(0) >= FULL_ACCESS_RANK

    /**
     * Чего не хватает до возможности. 0 — уже открыта. Нужно подсказке «до
     * ранга осталось N», а не для самой проверки.
     */
    fun referralsShort(qualifiedDirectReferrals: Int, capability: RankCapability): Int =
        (capability.minimumQualifiedReferrals - qualifiedDirectReferrals.coerceAtLeast(0))
            .coerceAtLeast(0)

    /**
     * Название ранга, с которого открывается возможность. Берётся из общей
     * лестницы рангов, чтобы подсказки и экран «Ранги» называли ступень
     * одинаково.
     */
    fun rankNameFor(capability: RankCapability): String =
        FileTransferRankPolicy.entitlement(capability.minimumQualifiedReferrals).rankName

    /**
     * Строгий рубеж для ViewModel-ей и сервисов: UI может быть обойдён (deep
     * link, старый клиент, прямой вызов), поэтому проверка повторяется там,
     * где действие реально начинается. Сообщение техническое (без ресурсов,
     * чтобы политика оставалась чистой и проверялась JVM-тестом); человеку
     * показывается текст из [com.vladimir.messenger.data.rank.RankEntitlementText].
     */
    fun requireAllowed(qualifiedDirectReferrals: Int, capability: RankCapability) {
        check(isAllowed(qualifiedDirectReferrals, capability)) {
            "${capability.name} opens at rank \"${rankNameFor(capability)}\" " +
                "(${capability.minimumQualifiedReferrals} qualified referrals); " +
                "confirmed: $qualifiedDirectReferrals"
        }
    }
}
