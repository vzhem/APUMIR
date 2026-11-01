package com.vladimir.messenger.data.update

/**
 * Состояние одной просьбы о файле обновления в рое.
 *
 * Вынесено из [ApkSeeder] по шву просьб/полос: сервис остаётся маршрутизатором
 * протокола, а память о текущем круге сидов живёт рядом маленькими классами.
 * Никакого Android: поля читает и помпа, и UI-слой через состояние сидера.
 */
internal class PendingPatchAsk(
    val fromVersion: String,
    val toVersion: String,
    val sha256: String,
    val apkSha256: String,
    val startedAtMs: Long,
) {
    var askedSeed: String? = null
    var askedAtMs: Long = 0L
    var attempts: Int = 0
    /** Кого уже спрашивали в этом круге: следующий — другой сид. */
    val tried = LinkedHashSet<String>()
    /** Кто уже шлёт полосы (предложение принято приёмником, K2). */
    val striping = LinkedHashSet<String>()
}

internal class PendingAsk(val version: String, val sha256: String, val startedAtMs: Long) {
    var askedSeed: String? = null
    var askedAtMs: Long = 0L
    var attempts: Int = 0
    /** Кого уже спрашивали в этом круге: следующий — другой сид. */
    val tried = LinkedHashSet<String>()
    /** Кто уже шлёт полосы (предложение принято приёмником, K2). */
    val striping = LinkedHashSet<String>()
}
