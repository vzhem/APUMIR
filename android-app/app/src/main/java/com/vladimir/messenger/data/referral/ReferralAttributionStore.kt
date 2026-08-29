package com.vladimir.messenger.data.referral

import android.content.Context

/**
 * Локальное хранилище реферальной атрибуции.
 *
 * SharedPreferences, а не Room: новая колонка потребовала бы миграции базы
 * (а AppModule включает fallbackToDestructiveMigration, то есть ошибка
 * миграции — это потеря данных), а здесь нужны всего два набора строк.
 *
 * Две стороны одной записи:
 * - приглашённый помнит, кто его пригласил, и отправил ли уже атрибуцию;
 * - пригласивший помнит, кого уже зачёл, поэтому повторный пакет (повторная
 *   доставка, повторная установка у приглашённого) счётчик не двигает.
 *
 * Как и [PendingReferralStore], объект не доверяет хранимому значению слепо:
 * каждый идентификатор перед записью проходит [ReferralWire.canonicalNodeId].
 */
object ReferralAttributionStore {

    const val PREFS_NAME = "apu_referral_attribution"

    private const val INVITER_PREFIX = "inviter_for_"
    private const val SENT_KEY = "attributed_contacts_v1"
    private const val CREDITED_KEY = "credited_invitees_v1"

    /** Ограничение на размер наборов, чтобы хранилище не росло бесконечно. */
    private const val MAX_TRACKED = 2_000

    @Synchronized
    fun rememberInviter(context: Context, contactId: String, inviterNodeId: String): Boolean =
        rememberInviterIn(context, PREFS_NAME, contactId, inviterNodeId)

    @Synchronized
    internal fun rememberInviterIn(
        context: Context,
        prefsName: String,
        contactId: String,
        inviterNodeId: String,
    ): Boolean {
        requireAllowedStore(prefsName)
        val key = contactKey(contactId) ?: return false
        val inviter = ReferralWire.canonicalNodeId(inviterNodeId) ?: return false
        return context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(INVITER_PREFIX + key, inviter)
            .commit()
    }

    /**
     * Кто пригласил этот контакт и кому ещё НЕ отправлена атрибуция.
     * null — либо контакт добавлен не по ссылке, либо атрибуция уже ушла.
     */
    @Synchronized
    fun pendingInviter(context: Context, contactId: String): String? =
        pendingInviterIn(context, PREFS_NAME, contactId)

    @Synchronized
    internal fun pendingInviterIn(context: Context, prefsName: String, contactId: String): String? {
        requireAllowedStore(prefsName)
        val key = contactKey(contactId) ?: return null
        val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val stored = prefs.getString(INVITER_PREFIX + key, null) ?: return null
        if ((prefs.getStringSet(SENT_KEY, emptySet()) ?: emptySet()).contains(key)) return null
        return ReferralWire.canonicalNodeId(stored)
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
        if (text.isEmpty() || text.length > ReferralWire.MAX_ENVELOPE_CHARS) return null
        return text
    }

    private fun requireAllowedStore(prefsName: String) {
        require(prefsName == PREFS_NAME || prefsName.startsWith("apu_referral_attribution_test_")) {
            "Unexpected referral attribution store"
        }
    }
}
