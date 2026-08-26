package com.vladimir.messenger.data.file

/**
 * Outgoing-media growth entitlements based only on qualified direct referrals.
 * Receiving, text messaging, security and transport priority are never rank-gated.
 */
object FileTransferRankPolicy {
    enum class Category { PHOTO, FILE, VIDEO }

    data class Entitlement(
        val minimumQualifiedReferrals: Int,
        val rankName: String,
        val allowedCategories: Set<Category>,
        val rankMaxBytes: Long,
    ) {
        val canCreateGroup: Boolean get() = minimumQualifiedReferrals >= 10
        val canUseAutomaticProxy: Boolean get() = minimumQualifiedReferrals >= 10
        val canUseManualProxy: Boolean get() = minimumQualifiedReferrals >= 1
        val canCreateChannel: Boolean get() = minimumQualifiedReferrals >= 30

        fun effectiveMaxBytes(technicalLimitBytes: Long): Long =
            minOf(rankMaxBytes, technicalLimitBytes)

        fun unlockedFeatureSummary(): List<String> = buildList {
            add("Текстовые сообщения")
            add("Вступление в группы и каналы")
            add("Получение файлов, фото и видео")
            if (Category.PHOTO in allowedCategories) add("Отправка фото")
            if (Category.FILE in allowedCategories) add("Отправка файлов")
            if (Category.VIDEO in allowedCategories) add("Отправка видео")
            if (canCreateGroup) add("Создание групп")
            if (canUseManualProxy) add("Ручное добавление прокси")
            if (canUseAutomaticProxy) add("Автосбор и автоматический выбор прокси")
            if (canCreateChannel) add("Создание каналов")
        }
    }

    private fun mib(value: Long): Long = value * 1024 * 1024
    private fun gib(value: Long): Long = value * 1024 * 1024 * 1024

    val tiers: List<Entitlement> = listOf(
        Entitlement(0, "Гость", emptySet(), 0),
        Entitlement(1, "Первый связной", setOf(Category.PHOTO), mib(5)),
        Entitlement(3, "Круг друзей", setOf(Category.PHOTO, Category.FILE), mib(10)),
        Entitlement(10, "Проводник", Category.entries.toSet(), mib(25)),
        Entitlement(20, "Организатор", Category.entries.toSet(), mib(50)),
        Entitlement(30, "Навигатор", Category.entries.toSet(), mib(100)),
        Entitlement(50, "Амбассадор", Category.entries.toSet(), mib(250)),
        Entitlement(100, "Строитель сообщества", Category.entries.toSet(), mib(500)),
        Entitlement(200, "Хранитель сети", Category.entries.toSet(), mib(750)),
        Entitlement(300, "Маяк APU", Category.entries.toSet(), gib(1)),
        Entitlement(500, "Лидер сообщества", Category.entries.toSet(), mib(1536)),
        Entitlement(700, "Легенда APU", Category.entries.toSet(), gib(2)),
        Entitlement(1000, "Создатель сети", Category.entries.toSet(), gib(4)),
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
        technicalLimitBytes: Long,
    ): Entitlement {
        require(sizeBytes >= 0 && technicalLimitBytes > 0)
        val entitlement = entitlement(qualifiedDirectReferrals)
        val category = categoryFor(mediaType)
        check(category in entitlement.allowedCategories) {
            "${category.name.lowercase()} sending requires a higher referral rank"
        }
        check(sizeBytes <= entitlement.effectiveMaxBytes(technicalLimitBytes)) {
            "File exceeds the current rank or technical limit"
        }
        return entitlement
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
