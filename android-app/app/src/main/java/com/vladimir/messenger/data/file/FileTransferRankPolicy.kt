package com.vladimir.messenger.data.file

/**
 * Community feature ranks based on qualified direct referrals.
 *
 * Решение владельца от 2026-08-27: текстовые сообщения доступны всегда, а
 * отправка файлов, фото, видео, GIF и стикеров открывается с третьего
 * подтверждённого приглашения (ранг «Круг друзей»). До этого отправка медиа
 * рангом не ограничивалась вовсе.
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
        val canCreateGroup: Boolean get() = minimumQualifiedReferrals >= 10
        val canUseAutomaticProxy: Boolean get() = minimumQualifiedReferrals >= 10
        val canUseManualProxy: Boolean get() = minimumQualifiedReferrals >= 1
        val canCreateChannel: Boolean get() = minimumQualifiedReferrals >= 30

        /** Десятый ранг («Проводник») и выше - это VIP. */
        val isVip: Boolean
            get() = minimumQualifiedReferrals >= VIP_MINIMUM_QUALIFIED_REFERRALS

        /** Отправка файлов, фото, видео, GIF и стикеров. Текст — без ограничений. */
        val canSendAttachments: Boolean get() = minimumQualifiedReferrals >= 3

        fun unlockedFeatureSummary(): List<String> = buildList {
            add("Текстовые сообщения")
            add("Вступление в группы и каналы")
            add("Получение файлов, фото и видео")
            if (canSendAttachments) {
                add("Отправка фото")
                add("Отправка файлов")
                add("Отправка видео")
            }
            if (canCreateGroup) add("Создание групп")
            if (canUseManualProxy) add("Ручное добавление прокси")
            if (canUseAutomaticProxy) add("Автосбор и автоматический выбор прокси")
            if (canCreateChannel) add("Создание каналов")
        }
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
     * Проверка перед отправкой вложения. Размер и тип файла рангом не
     * ограничены, ограничена сама возможность отправить что-то кроме текста.
     * Сообщение об отказе показывается владельцу дословно, поэтому оно на
     * русском и говорит, чего не хватает.
     */
    fun requireCanSend(
        qualifiedDirectReferrals: Int,
        mediaType: String,
        sizeBytes: Long,
    ): Entitlement {
        require(sizeBytes >= 0)
        require(mediaType.isNotBlank())
        val current = entitlement(qualifiedDirectReferrals)
        check(current.canSendAttachments) {
            "Отправка файлов, фото и видео открывается с ранга «Круг друзей» — " +
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
        entitlement(qualifiedDirectReferrals).canSendAttachments

    /** Basic communication and joining communities remain available immediately after install. */
    fun canSendTextAtAnyRank(): Boolean = true
    fun canJoinGroupsAtAnyRank(): Boolean = true
    fun canJoinChannelsAtAnyRank(): Boolean = true

    /** Incoming media cannot be held hostage by sender/recipient rank changes. */
    fun canReceiveAtAnyRank(): Boolean = true
}
