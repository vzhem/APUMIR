package com.vladimir.messenger.data.referral

import android.content.Context

/**
 * Локальное хранилище реферальной атрибуции.
 *
 * SharedPreferences, а не Room: новая колонка потребовала бы миграции базы
 * (а AppModule включает fallbackToDestructiveMigration, то есть ошибка
 * миграции — это потеря данных), а здесь нужны несколько строк и два набора.
 *
 * Две стороны одной записи:
 * - приглашённый помнит, кто его пригласил, подписанный токен из ссылки и
 *   отправлена ли уже атрибуция;
 * - пригласивший помнит, кого уже зачёл, поэтому повторный пакет (повторная
 *   доставка, повторная установка у приглашённого) счётчик не двигает.
 *
 * Как и [PendingReferralStore], объект не доверяет хранимому значению слепо:
 * каждый идентификатор перед записью проходит [ReferralWire.canonicalNodeId].
 */
object ReferralAttributionStore {

    const val PREFS_NAME = "apu_referral_attribution"

    private const val INVITER_PREFIX = "inviter_for_"
    private const val TOKEN_PREFIX = "token_for_"
    private const val SENT_KEY = "attributed_contacts_v1"
    private const val CREDITED_KEY = "credited_invitees_v1"

    /**
     * Последний отказ с причиной. Нужен для диагностики на телефоне: буфер
     * logcat вытесняется быстро, а здесь видно, почему приглашение не
     * засчиталось (см. scripts/referral-proof.ps1).
     */
    private const val LAST_REJECTION_KEY = "last_rejection_v1"

    /** Ограничение на размер наборов, чтобы хранилище не росло бесконечно. */
    private const val MAX_TRACKED = 2_000

    /** Как [PendingReferralStore.MAX_ENCODED_TOKEN_CHARS]: токен в base64url. */
    private const val MAX_ENCODED_TOKEN_CHARS = 700

    data class PendingAttribution(
        val inviterNodeId: String,
        val tokenB64: String,
    )

    @Synchronized
    fun rememberInviter(
        context: Context,
        contactId: String,
        inviterNodeId: String,
        tokenB64: String,
    ): Boolean = rememberInviterIn(context, PREFS_NAME, contactId, inviterNodeId, tokenB64)

    @Synchronized
    internal fun rememberInviterIn(
        context: Context,
        prefsName: String,
        contactId: String,
        inviterNodeId: String,
        tokenB64: String,
    ): Boolean {
        requireAllowedStore(prefsName)
        val key = contactKey(contactId) ?: return false
        val inviter = ReferralWire.canonicalNodeId(inviterNodeId) ?: return false
        if (tokenB64.isEmpty() || tokenB64.length > MAX_ENCODED_TOKEN_CHARS) return false
        if (ReferralWire.decode(tokenB64) == null) return false
        return context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(INVITER_PREFIX + key, inviter)
            .putString(TOKEN_PREFIX + key, tokenB64)
            .commit()
    }

    /**
     * Кого этот контакт пришёл по ссылке и каким токеном, если атрибуция ещё
     * не отправлена. null — контакт добавлен не по ссылке либо пакет уже ушёл.
     */
    @Synchronized
    fun pendingAttribution(context: Context, contactId: String): PendingAttribution? =
        pendingAttributionIn(context, PREFS_NAME, contactId)

    @Synchronized
    internal fun pendingAttributionIn(
        context: Context,
        prefsName: String,
        contactId: String,
    ): PendingAttribution? {
        requireAllowedStore(prefsName)
        val key = contactKey(contactId) ?: return null
        val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val storedInviter = prefs.getString(INVITER_PREFIX + key, null) ?: return null
        val storedToken = prefs.getString(TOKEN_PREFIX + key, null) ?: return null
        if ((prefs.getStringSet(SENT_KEY, emptySet()) ?: emptySet()).contains(key)) return null
        val inviter = ReferralWire.canonicalNodeId(storedInviter) ?: return null
        if (storedToken.isEmpty() || storedToken.length > MAX_ENCODED_TOKEN_CHARS) return null
        return PendingAttribution(inviter, storedToken)
    }

    @Synchronized
    fun markAttributionSent(context: Context, contactId: String): Boolean =
        markAttributionSentIn(context, PREFS_NAME, contactId)

    @Synchronized
    internal fun markAttributionSentIn(context: Context, prefsName: String, contactId: String): Boolean {
        requireAllowedStore(prefsName)
        val key = contactKey(contactId) ?: return false
        return addToSet(context, prefsName, SENT_KEY, key)
    }

    /** Кого этот телефон уже зачёл себе как приглашённого. */
    @Synchronized
    fun creditedInvitees(context: Context): Set<String> =
        creditedInviteesIn(context, PREFS_NAME)

    @Synchronized
    internal fun creditedInviteesIn(context: Context, prefsName: String): Set<String> {
        requireAllowedStore(prefsName)
        val stored = context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getStringSet(CREDITED_KEY, emptySet())
            ?: emptySet()
        return stored.mapNotNull { ReferralWire.canonicalNodeId(it) }.toSet()
    }

    /**
     * Отметить приглашённого зачисленным.
     *
     * @return true только если запись новая — именно это значит, что счётчик
     *   нужно увеличить. Повторный вызов для того же узла даёт false.
     */
    @Synchronized
    fun markCredited(context: Context, inviteeNodeId: String): Boolean =
        markCreditedIn(context, PREFS_NAME, inviteeNodeId)

    @Synchronized
    internal fun markCreditedIn(context: Context, prefsName: String, inviteeNodeId: String): Boolean {
        requireAllowedStore(prefsName)
        val invitee = ReferralWire.canonicalNodeId(inviteeNodeId) ?: return false
        val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(CREDITED_KEY, emptySet()) ?: emptySet()
        if (existing.any { it.equals(invitee, ignoreCase = true) }) return false
        return addToSet(context, prefsName, CREDITED_KEY, invitee)
    }

    /** Запомнить последний отказ — для отчёта на телефоне, не для логики. */
    @Synchronized
    fun recordRejection(context: Context, senderId: String, reason: String, nowMs: Long): Boolean =
        recordRejectionIn(context, PREFS_NAME, senderId, reason, nowMs)

    @Synchronized
    internal fun recordRejectionIn(
        context: Context,
        prefsName: String,
        senderId: String,
        reason: String,
        nowMs: Long,
    ): Boolean {
        requireAllowedStore(prefsName)
        val safeSender = ReferralWire.canonicalNodeId(senderId) ?: senderId.take(80)
        val safeReason = reason.replace('|', '/').take(120)
        return context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_REJECTION_KEY, "$safeSender|$safeReason|$nowMs")
            .commit()
    }

    @Synchronized
    fun lastRejection(context: Context): String? = lastRejectionIn(context, PREFS_NAME)

    @Synchronized
    internal fun lastRejectionIn(context: Context, prefsName: String): String? {
        requireAllowedStore(prefsName)
        return context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(LAST_REJECTION_KEY, null)
    }

    @Synchronized
    fun clear(context: Context): Boolean = clearIn(context, PREFS_NAME)

    @Synchronized
    internal fun clearIn(context: Context, prefsName: String): Boolean {
        requireAllowedStore(prefsName)
        return context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun addToSet(context: Context, prefsName: String, key: String, value: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        // getStringSet возвращает живой набор прежнего значения, поэтому копируем.
        val updated = LinkedHashSet<String>()
        updated.addAll(prefs.getStringSet(key, emptySet()) ?: emptySet())
        updated.add(value)
        while (updated.size > MAX_TRACKED) {
            val oldest = updated.iterator().next()
            updated.remove(oldest)
        }
        return prefs.edit().putStringSet(key, updated).commit()
    }

    private fun contactKey(contactId: String): String? {
        val text = contactId.trim()
        if (text.isEmpty() || text.length > 128) return null
        return text
    }

    private fun requireAllowedStore(prefsName: String) {
        require(prefsName == PREFS_NAME || prefsName.startsWith("apu_referral_attribution_test_")) {
            "Unexpected referral attribution store"
        }
    }
}
