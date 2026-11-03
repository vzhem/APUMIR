package com.vladimir.messenger.data.file

import com.vladimir.messenger.data.rank.FeatureRankPolicy
import com.vladimir.messenger.data.rank.RankCapability
import com.vladimir.messenger.data.rank.RankFeatureSummary

/**
 * Community feature ranks based on rank-equivalent units: 1,000 game hearts or one confirmed direct referral.
 *
 * Ранг-эквивалент растёт от общей шкалы: 1 000 игровых сердечек дают один
 * шаг. Подтверждённое прямое приглашение добавляет в игру те же 1 000 сердечек.
 *
 * Решение владельца от 2026-08-27: текстовые сообщения доступны всегда, а
 * отправка файлов, фото, GIF и стикеров открывается с третьего подтверждённого
 * приглашения (ранг «Круг друзей»). Решение от 2026-11-03: до третьего ранга —
 * только минимум, а видео открывается позже (с «Проводника», 10). Пороги
 * живут в одном месте — `data/rank/FeatureRankPolicy.kt`.
 *
 * Приём входящих файлов рангом не ограничивается никогда: иначе чужой ранг
 * решал бы, увидите вы уже отправленное вам или нет. Размер файла рангом не
 * ограничивается тоже — только сама возможность отправить не-текст.
 */
object FileTransferRankPolicy {
    enum class Category { PHOTO, FILE, VIDEO }

    /**
     * VIP — «элита» приложения. Решение владельца от 2026-10-07: «Нужно чтобы
     * ранг 10 Проводник уже был VIP. Также чтобы аккаунт VIP должно быть видно
     * везде и в группах и в каналах».
     *
     * Десятка — это «Проводник» (10 подтверждённых приглашений), и он уже VIP:
     * элита начинается с него, а не со следующей ступени. Порог лежит здесь, а
     * не в интерфейсе: по нему решают и список рангов, и знак рядом с именем, и
     * золотое кольцо вокруг аватарки.
     * Ещё раз: VIP — это знак признания, а не дополнительная возможность.
     * Возможности по-прежнему открываются рангами (см. [Entitlement]).
     */
    const val VIP_MINIMUM_QUALIFIED_REFERRALS = 10

    data class Entitlement(
        val minimumQualifiedReferrals: Int,
        val rankName: String,
    ) {
        // Пороги берутся из единого списка возможностей, а не дублируются
        // здесь числом: иначе правило живёт в двух местах и разъезжается.
        val canCreateGroup: Boolean
            get() = minimumQualifiedReferrals >= RankCapability.CREATE_GROUP.minimumQualifiedReferrals
        val canUseAutomaticProxy: Boolean
            get() = minimumQualifiedReferrals >= RankCapability.PROXY_AUTOMATIC.minimumQualifiedReferrals
        val canUseManualProxy: Boolean
            get() = minimumQualifiedReferrals >= RankCapability.PROXY_MANUAL.minimumQualifiedReferrals
        val canCreateChannel: Boolean
            get() = minimumQualifiedReferrals >= RankCapability.CREATE_CHANNEL.minimumQualifiedReferrals

        /** Десятый ранг («Проводник») и выше - это VIP. */
        val isVip: Boolean
            get() = minimumQualifiedReferrals >= VIP_MINIMUM_QUALIFIED_REFERRALS

        /**
         * Отправка фото, документов, голосовых, GIF и стикеров — с ранга
         * «Круг друзей» (3). Видео в это право не входит: оно тяжелее всего
         * для роя и открывается отдельно, с «Проводника» (решение владельца
         * 2026-11-03, см. [RankCapability.SEND_VIDEO]).
         */
        val canSendAttachments: Boolean get() = canSendPhotosAndFiles

        /** Фото, документы, голосовые, GIF и стикеры. */
        val canSendPhotosAndFiles: Boolean
            get() = minimumQualifiedReferrals >= FeatureRankPolicy.CIRCLE_RANK

        /** Видео: с «Проводника» (10). */
        val canSendVideo: Boolean
            get() = minimumQualifiedReferrals >= RankCapability.SEND_VIDEO.minimumQualifiedReferrals

        /** Публикация историй, геолокация, звонки и опросы — с «Круга друзей». */
        val canUseCircleFeatures: Boolean
            get() = minimumQualifiedReferrals >= FeatureRankPolicy.CIRCLE_RANK

        fun unlockedFeatureSummary(): List<String> =
            RankFeatureSummary.describe(minimumQualifiedReferrals)
    }

    val tiers: List<Entitlement> = listOf(
        Entitlement(0, "Гость"),
        Entitlement(1, "Первый связной"),
        Entitlement(3, "Круг друзей"),
        Entitlement(10, "Проводник"),
        Entitlement(20, "Организатор"),
        Entitlement(30, "Навигатор"),
        Entitlement(50, "Амбассадор"),
        Entitlement(100, "Строитель сообщества"),
        Entitlement(200, "Хранитель сети"),
        Entitlement(300, "Маяк APU"),
        Entitlement(500, "Лидер сообщества"),
        Entitlement(700, "Легенда APU"),
        Entitlement(1000, "Создатель сети"),
    )

    /** Обычные ранги: всё, что ниже «Проводника». */
    val regularTiers: List<Entitlement> get() = tiers.filterNot { it.isVip }

    /** VIP-ранги: «Проводник» и выше - элита приложения. */
    val vipTiers: List<Entitlement> get() = tiers.filter { it.isVip }

    /** Сколько приглашений нужно, чтобы войти в VIP. Для подсказок в интерфейсе. */
    val vipMinimumReferrals: Int get() = VIP_MINIMUM_QUALIFIED_REFERRALS

    /** VIP ли этот ранг: с десятого, то есть «Проводник» и дальше. */
    fun isVip(qualifiedDirectReferrals: Int): Boolean =
        qualifiedDirectReferrals >= VIP_MINIMUM_QUALIFIED_REFERRALS

    /**
     * Сколько подтверждённых приглашений осталось до VIP. Ноль, если VIP уже есть:
     * экран рангов показывает это как «вы в элите», а не как «осталось 0».
     */
    fun referralsToVip(qualifiedDirectReferrals: Int): Int =
        (VIP_MINIMUM_QUALIFIED_REFERRALS - qualifiedDirectReferrals).coerceAtLeast(0)

    /**
     * Следующая ступень после текущей или null, если ранг уже наивысший.
     * Нужна экрану рангов, чтобы честно сказать, что откроется дальше.
     */
    fun nextTier(qualifiedDirectReferrals: Int): Entitlement? =
        tiers.firstOrNull { qualifiedDirectReferrals < it.minimumQualifiedReferrals }

    fun entitlement(qualifiedDirectReferrals: Int): Entitlement {
        require(qualifiedDirectReferrals >= 0)
        return tiers.last { qualifiedDirectReferrals >= it.minimumQualifiedReferrals }
    }

    fun categoryFor(mediaType: String): Category = when {
        mediaType.startsWith("image/") -> Category.PHOTO
        mediaType.startsWith("video/") -> Category.VIDEO
        else -> Category.FILE
    }

    /**
     * Проверка перед отправкой вложения. Размер файла рангом не ограничен
     * никогда; ранг открывает сам тип: фото, документы, голосовые, GIF и
     * стикеры — с «Круга друзей» (3), видео — с «Проводника» (10). Текстовые
     * сообщения доступны всегда.
     */
    fun requireCanSend(
        qualifiedDirectReferrals: Int,
        mediaType: String,
        sizeBytes: Long,
    ): Entitlement {
        require(sizeBytes >= 0)
        require(mediaType.isNotBlank())
        val current = entitlement(qualifiedDirectReferrals)
        if (categoryFor(mediaType) == Category.VIDEO) {
            check(current.canSendVideo) {
                "Отправка видео открывается с ранга «Проводник» — " +
                    "это 10 подтверждённых приглашений. Сейчас подтверждено: " +
                    "$qualifiedDirectReferrals. Фото, файлы и голосовые доступны с ранга «Круг друзей»."
            }
            return current
        }
        check(current.canSendAttachments) {
            "Отправка файлов, фото и голосовых открывается с ранга «Круг друзей» — " +
                "это 3 подтверждённых приглашения. Сейчас подтверждено: " +
                "$qualifiedDirectReferrals. Текстовые сообщения доступны без ограничений."
        }
        return current
    }

    fun canCreateGroup(qualifiedDirectReferrals: Int): Boolean =
        entitlement(qualifiedDirectReferrals).canCreateGroup

    fun canCreateChannel(qualifiedDirectReferrals: Int): Boolean =
        entitlement(qualifiedDirectReferrals).canCreateChannel

    fun canUseAutomaticProxy(qualifiedDirectReferrals: Int): Boolean =
        entitlement(qualifiedDirectReferrals).canUseAutomaticProxy

    fun canUseManualProxy(qualifiedDirectReferrals: Int): Boolean =
        entitlement(qualifiedDirectReferrals).canUseManualProxy

    /** Для интерфейса: показывать ли кнопку вложения и что писать в подсказке. */
    fun canSendAttachments(qualifiedDirectReferrals: Int): Boolean =
        entitlement(qualifiedDirectReferrals).canSendPhotosAndFiles

    /** Для интерфейса: открыта ли отправка видео («Проводник», 10). */
    fun canSendVideo(qualifiedDirectReferrals: Int): Boolean =
        entitlement(qualifiedDirectReferrals).canSendVideo

    /** Basic communication and joining communities remain available immediately after install. */
    fun canSendTextAtAnyRank(): Boolean = true
    fun canJoinGroupsAtAnyRank(): Boolean = true
    fun canJoinChannelsAtAnyRank(): Boolean = true

    /** Incoming media cannot be held hostage by sender/recipient rank changes. */
    fun canReceiveAtAnyRank(): Boolean = true
}
