package com.vladimir.messenger.data.referral

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Приём реферальной атрибуции из общего потока сообщений.
 *
 * Подключается в CoreServerService.handleEvent рядом с роутерами файловых и
 * групповых пакетов — так же ДО авто-создания контакта, иначе служебный
 * конверт превратился бы в личный чат с отправителем и попал в историю как
 * обычный текст.
 *
 * Возвращает true, если текст оказался реферальным конвертом и обработан, —
 * даже когда разобрать его не удалось: битый конверт намеренно поглощается.
 */
@Singleton
class ReferralAttributionRouter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun routeIncoming(
        senderId: String,
        text: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!ReferralWire.isReferralPacket(text)) return false

        val app = context.applicationContext
        val packet = ReferralWire.parse(text)
        if (packet == null) {
            Log.w(TAG, "referral packet from $senderId is malformed, dropped")
            return true
        }

        val decision = ReferralCreditPolicy.decide(
            packet = packet,
            transportSenderId = senderId,
            ownNodeId = RustBridge.nodeId(),
            alreadyCredited = ReferralAttributionStore.creditedInvitees(app),
            nowMs = nowMs,
        )

        when (decision) {
            is ReferralCreditPolicy.Decision.Credit -> {
                // Порядок важен: сначала отметка о зачислении, потом счётчик.
                // Если сделать наоборот, сбой между двумя записями позволил бы
                // одному и тому же пакету поднять счётчик дважды.
                val fresh = ReferralAttributionStore.markCredited(app, decision.inviteeNodeId)
                if (fresh) {
                    val total = ReferralRankStore.creditQualifiedDirect(app)
                    Log.i(TAG, "referral credited from ${decision.inviteeNodeId}, qualified=$total")
                } else {
                    Log.i(TAG, "referral from ${decision.inviteeNodeId} already recorded, count unchanged")
                }
            }
            is ReferralCreditPolicy.Decision.Reject -> {
                Log.i(TAG, "referral from $senderId not credited: ${decision.reason}")
            }
        }
        return true
    }

    private companion object {
        const val TAG = "ReferralRouter"
    }
}
