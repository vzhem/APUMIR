package com.vladimir.messenger.data.referral

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
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
 * даже когда разобрать или подтвердить его не удалось: битый и неподписанный
 * конверт намеренно поглощается.
 */
@Singleton
class ReferralAttributionRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attributionSender: ReferralAttributionSender,
) {

    fun routeIncoming(
        senderId: String,
        text: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!ReferralWire.isReferralPacket(text)) return false

        val app = context.applicationContext
        when (val packet = ReferralWire.parse(text)) {
            null -> {
                Log.w(TAG, "referral packet from $senderId is malformed, dropped")
                recordRejection(app, senderId, "malformed envelope", nowMs)
            }

            is ReferralWire.Packet.UnsignedAttribution -> {
                // Версия 1 была без подписи: принимать её значит позволить
                // накрутку чужим идентификатором. Поглощаем, но не зачисляем.
                Log.i(TAG, "unsigned referral from $senderId ignored")
                recordRejection(app, senderId, "unsigned envelope is not credited", nowMs)
            }

            is ReferralWire.Packet.TokenRequest -> {
                // Нас пригласили по короткой ссылке, и приглашённый просит
                // подпись. Отвечаем тем же токеном, что раньше ехал в ссылке.
                val token = IdentitySigningKeyStore.createSignedReferralToken(app)
                if (token == null) {
                    Log.w(TAG, "token request from $senderId: подпись недоступна")
                } else {
                    val reply = ReferralWire.buildTokenReply(
                        ReferralWire.canonicalNodeId(RustBridge.nodeId()).orEmpty(),
                        token,
                    )
                    if (reply != null) {
                        RustBridge.sendMessage(
                            java.util.UUID.randomUUID().toString(),
                            "referral",
                            senderId,
                            reply,
                        )
                        Log.i(TAG, "token reply sent to $senderId")
                    }
                }
            }

            is ReferralWire.Packet.TokenReply -> {
                // Пригласивший прислал подпись: сохраняем и отправляем обычную
                // атрибуцию тем же путём, что и раньше.
                val token = ReferralWire.decode(packet.tokenB64)
                if (token == null) {
                    Log.w(TAG, "token reply from $senderId: битая подпись")
                } else if (attributionSender.rememberInviter(senderId, packet.fromNodeId, token)) {
                    Log.i(TAG, "token reply from $senderId принят, отправляем атрибуцию")
                    attributionSender.sendPending("referral", senderId)
                }
            }

            is ReferralWire.Packet.SignedAttribution -> {
                val receipt = ReferralReceiptVerifier.verify(packet, nowMs)
                if (receipt == null) {
                    Log.w(TAG, "referral receipt from $senderId failed signature or token checks")
                    recordRejection(app, senderId, "signature or token verification failed", nowMs)
                } else {
                    credit(app, senderId, receipt, nowMs)
                }
            }
        }
        return true
    }

    private fun credit(
        app: Context,
        senderId: String,
        receipt: VerifiedReceipt,
        nowMs: Long,
    ) {
        val decision = ReferralCreditPolicy.decide(
            receipt = receipt,
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
                    Log.i(TAG, "referral from ${decision.inviteeNodeId} already recorded")
                }
            }

            is ReferralCreditPolicy.Decision.Reject -> {
                Log.i(TAG, "referral from $senderId not credited: ${decision.reason}")
                recordRejection(app, senderId, decision.reason, nowMs)
            }
        }
    }

    private fun recordRejection(app: Context, senderId: String, reason: String, nowMs: Long) {
        try {
            ReferralAttributionStore.recordRejection(app, senderId, reason, nowMs)
        } catch (e: Exception) {
            Log.w(TAG, "could not record the rejection reason: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ReferralRouter"
    }
}
