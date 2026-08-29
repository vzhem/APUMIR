package com.vladimir.messenger.data.referral

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Отправка реферальной атрибуции на стороне приглашённого.
 *
 * Моменты, когда атрибуция уходит пригласившему:
 * 1. сразу после того, как контакт добавлен по ссылке (чат создан);
 * 2. при первой отправке сообщения этому контакту — на случай, если в первый
 *    момент транспорта не было.
 *
 * Отправка идемпотентна: после успешной передачи контакт помечается, и дальше
 * [sendPending] для него ничего не делает. Пакет идёт тем же транспортом 1:1,
 * что и групповые конверты, минуя историю чата.
 */
@Singleton
class ReferralAttributionSender @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val random = SecureRandom()

    /** Запомнить, что контакт добавлен по пригласительной ссылке/QR. */
    fun rememberInviter(contactId: String, inviterNodeId: String): Boolean {
        return try {
            ReferralAttributionStore.rememberInviter(context.applicationContext, contactId, inviterNodeId)
        } catch (e: Exception) {
            Log.w(TAG, "rememberInviter failed for $contactId: ${e.message}")
            false
        }
    }

    /**
     * Отправить атрибуцию, если она ещё не отправлена для этого контакта.
     *
     * @return true, если пакет ушёл транспортом. Ложный результат не ошибка:
     *   попытка повторится на следующем сообщении.
     */
    fun sendPending(chatId: String, contactId: String): Boolean {
        val app = context.applicationContext
        return try {
            val inviter = ReferralAttributionStore.pendingInviter(app, contactId) ?: return false
            val own = ReferralWire.canonicalNodeId(RustBridge.nodeId())
            if (own == null) {
                Log.w(TAG, "referral attribution skipped: own node id unavailable")
                return false
            }
            if (own.equals(inviter, ignoreCase = true)) {
                // Своя же ссылка (например, отсканировали собственный QR):
                // помечаем отправленной, чтобы не пробовать на каждом сообщении.
                ReferralAttributionStore.markAttributionSent(app, contactId)
                return false
            }
            val nonce = ByteArray(ReferralWire.NONCE_BYTES)
            random.nextBytes(nonce)
            val envelope = ReferralWire.buildAttribution(own, inviter, System.currentTimeMillis(), nonce)
                ?: return false
            val sent = RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, inviter, envelope)
            if (sent) {
                ReferralAttributionStore.markAttributionSent(app, contactId)
                Log.i(TAG, "referral attribution sent to $inviter for contact $contactId")
            } else {
                Log.i(TAG, "referral attribution to $inviter not delivered yet, will retry")
            }
            sent
        } catch (e: Exception) {
            Log.w(TAG, "sendPending failed for $contactId: ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "ReferralAttribution"
    }
}
