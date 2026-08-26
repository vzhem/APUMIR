package com.vladimir.messenger.data.file

/**
 * Community feature ranks based on qualified direct referrals.
 * Sending/receiving media, text, security and transport priority are never rank-gated.
 */
object FileTransferRankPolicy {
    enum class Category { PHOTO, FILE, VIDEO }

    data class Entitlement(
        val minimumQualifiedReferrals: Int,
        val rankName: String,
    ) {
        val canCreateGroup: Boolean get() = minimumQualifiedReferrals >= 10
        val canUseAutomaticProxy: Boolean get() = minimumQualifiedReferrals >= 10
        val canUseManualProxy: Boolean get() = minimumQualifiedReferrals >= 1
        val canCreateChannel: Boolean get() = minimumQualifiedReferrals >= 30

        fun unlockedFeatureSummary(): List<String> = buildList {
            add("Текстовые сообщения")
            add("Вступление в группы и каналы")
            add("Получение файлов, фото и видео")
            add("Отправка фото")
            add("Отправка файлов")
            add("Отправка видео")
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

    fun entitlement(qualifiedDirectReferrals: Int): Entitlement {
        require(qualifiedDirectReferrals >= 0)
        return tiers.last { qualifiedDirectReferrals >= it.minimumQualifiedReferrals }
    }

    fun categoryFor(mediaType: String): Category = when {
        mediaType.startsWith("image/") -> Category.PHOTO
        mediaType.startsWith("video/") -> Category.VIDEO
        else -> Category.FILE
    }

    fun requireCanSend(
        qualifiedDirectReferrals: Int,
        mediaType: String,
        sizeBytes: Long,
    ): Entitlement {
        require(sizeBytes >= 0)
        // File transfer is basic communication: rank never gates category or byte length.
        require(mediaType.isNotBlank())
        return entitlement(qualifiedDirectReferrals)
    }

    fun canCreateGroup(qualifiedDirectReferrals: Int): Boolean =
        entitlement(qualifiedDirectReferrals).canCreateGroup

    fun canCreateChannel(qualifiedDirectReferrals: Int): Boolean =
        entitlement(qualifiedDirectReferrals).canCreateChannel

    fun canUseAutomaticProxy(qualifiedDirectReferrals: Int): Boolean =
        entitlement(qualifiedDirectReferrals).canUseAutomaticProxy

    fun canUseManualProxy(qualifiedDirectReferrals: Int): Boolean =
        entitlement(qualifiedDirectReferrals).canUseManualProxy

    /** Basic communication and joining communities remain available immediately after install. */
    fun canSendTextAtAnyRank(): Boolean = true
    fun canJoinGroupsAtAnyRank(): Boolean = true
    fun canJoinChannelsAtAnyRank(): Boolean = true

    /** Incoming media cannot be held hostage by sender/recipient rank changes. */
    fun canReceiveAtAnyRank(): Boolean = true
}
