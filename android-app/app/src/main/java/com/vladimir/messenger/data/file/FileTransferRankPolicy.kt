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
        fun effectiveMaxBytes(technicalLimitBytes: Long): Long =
            minOf(rankMaxBytes, technicalLimitBytes)
    }

    private fun mib(value: Long): Long = value * 1024 * 1024
    private fun gib(value: Long): Long = value * 1024 * 1024 * 1024

    val tiers: List<Entitlement> = listOf(
        Entitlement(0, "Без ранга", emptySet(), 0),
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

    /** Incoming media cannot be held hostage by sender/recipient rank changes. */
    fun canReceiveAtAnyRank(): Boolean = true
}
