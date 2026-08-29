package com.vladimir.messenger.data.referral

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Отправка подписанной реферальной атрибуции на стороне приглашённого.
 *
 * Моменты, когда атрибуция уходит пригласившему:
 * 1. сразу после того, как контакт добавлен по ссылке (чат создан);
 * 2. при первой отправке сообщения этому контакту — на случай, если в первый
 *    момент транспорта или подписанной identity ещё не было.
 *
 * Отправка идемпотентна: после успешной передачи контакт помечается, и дальше
 * [sendPending] для него ничего не делает. Пакет идёт тем же транспортом 1:1,
 * что и групповые конверты, минуя историю чата.
 */
@Singleton
class ReferralAttributionSender @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Запомнить приглашение из ссылки: узел пригласившего и его подписанный
     * токен. Токен кладётся и в штатный [PendingReferralStore], который
     * проверяет подпись при каждом чтении.
     */
    fun rememberInviter(contactId: String, inviterNodeId: String, token: ByteArray): Boolean {
        return try {
            val app = context.applicationContext
            val saved = ReferralAttributionStore.rememberInviter(
                app,
                contactId,
                inviterNodeId,
                ReferralWire.encode(token),
            )
            if (saved) {
                PendingReferralStore.saveVerified(app, token)
            }
            saved
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
            val pending = ReferralAttributionStore.pendingAttribution(app, contactId) ?: return false
            val own = ReferralWire.canonicalNodeId(RustBridge.nodeId())
            if (own == null) {
                Log.w(TAG, "referral attribution skipped: own node id unavailable")
                return false
            }
            if (own.equals(pending.inviterNodeId, ignoreCase = true)) {
                // Своя же ссылка (отсканировали собственный QR): помечаем
                // отправленной, чтобы не пробовать на каждом сообщении.
                ReferralAttributionStore.markAttributionSent(app, contactId)
                return false
            }
            val token = ReferralWire.decode(pending.tokenB64) ?: return false
            val binding = IdentitySigningKeyStore.existingVerifiedBinding(app)
            if (binding == null) {
                Log.w(TAG, "referral attribution skipped: signed identity is not installed yet")
                return false
            }
            val envelope = ReferralWire.buildSignedAttribution(
                inviteeNodeId = own,
                inviterNodeId = pending.inviterNodeId,
                qualifiedAtMs = System.currentTimeMillis(),
                token = token,
                binding = binding,
            ) ?: return false
            val sent = RustBridge.sendMessage(
                UUID.randomUUID().toString(),
                chatId,
                pending.inviterNodeId,
                envelope,
            )
            if (sent) {
                ReferralAttributionStore.markAttributionSent(app, contactId)
                Log.i(TAG, "signed referral attribution sent to ${pending.inviterNodeId}")
            } else {
                Log.i(TAG, "referral attribution to ${pending.inviterNodeId} not delivered yet")
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
