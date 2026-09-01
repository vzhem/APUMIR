package com.vladimir.messenger.data.call

/**
 * Машина состояний звонка (дизайн: docs/CALLS_BOOTSTRAP.md, раздел 8.3).
 *
 * Чистый Kotlin без Android и сети: на вход — события и время, на выход —
 * эффекты, которые исполняет CallManager (отправка пакетов, звук, уведомления).
 * Поэтому вся логика таймаутов unit-тестируется в обычной JVM.
 *
 * Звонящий:  OFFERING → RINGING → CONNECTING → ACTIVE → ENDED
 * Принимающий: INCOMING → CONNECTING → ACTIVE → ENDED
 *
 * Таймауты (tick):
 *  - OFFERING: offer повторяется каждые 3 с, всего до 30 с → «Нет ответа».
 *  - RINGING: 60 с гудков → bye|timeout, «Нет ответа».
 *  - INCOMING: 45 с ожидания решения пользователя → локальное молчание
 *    (звонящий сам отвалится по своему таймауту).
 *  - CONNECTING: 12 с настройки звука → bye|failed.
 *  - ACTIVE: 5 с без кадров → «восстановление», 20 с → bye|failed.
 */
class CallStateMachine(
    val callId: String,
    val peerId: String,
    val outgoing: Boolean,
    private val startedAtMs: Long,
) {

    enum class Phase { IDLE, OFFERING, RINGING, INCOMING, CONNECTING, ACTIVE, ENDED }

    /** Эффекты для CallManager. */
    sealed interface Effect {
        data class SendOffer(val attempt: Int) : Effect
        object SendRing : Effect
        object SendAccept : Effect
        data class SendReject(val reason: String) : Effect
        data class SendBye(val reason: String, val attempt: Int) : Effect
        /** Настроить звуковой канал (сокет/движок). Один раз за CONNECTING. */
        object StartMedia : Effect
        /** Голос пошёл двусторонне (первый принятый кадр). */
        object MarkMediaUp : Effect
        object StopMedia : Effect
        /** Зажечь входящий звонок: уведомление + рингтон. */
        object NotifyIncoming : Effect
        /** Погасить входящий: стоп рингтона и уведомления. */
        object CancelIncoming : Effect
    }

    var phase: Phase = if (outgoing) Phase.OFFERING else Phase.INCOMING
        private set

    /** Причина конца в ENDED: bye/reject reason или "missed". */
    var endReason: String? = null
        private set

    /** ACTIVE после 5 с тишины: интерфейс показывает «восстановление соединения». */
    var recovering: Boolean = false
        private set

    /** Время перехода в ACTIVE (таймер разговора), 0 пока не разговариваем. */
    var connectedAtMs: Long = 0L
        private set

    private var offerAttempts = 0
    private var lastOfferAtMs = 0L
    private var byeAttempts = 0
    private var phaseChangedAtMs = startedAtMs
    private var lastMediaAtMs = 0L

    /** Время входа в текущую фазу (для таймаутов). */
    fun phaseChangedAt(): Long = phaseChangedAtMs

    // ── События от провода ──────────────────────────────────────────────────

    /** Звонящему пришёл ring: трубку ещё не взяли, но телефон трезвонит. */
    fun onRing(nowMs: Long): List<Effect> {
        if (!outgoing || phase != Phase.OFFERING) return emptyList()
        moveTo(Phase.RINGING, nowMs)
        return emptyList()
    }

    /** Звонящему пришёл accept: пора настраивать звук. */
    fun onAccept(nowMs: Long): List<Effect> {
        if (!outgoing || (phase != Phase.OFFERING && phase != Phase.RINGING)) return emptyList()
        moveTo(Phase.CONNECTING, nowMs)
        return listOf(Effect.StartMedia)
    }

    /** Пришёл reject (звонящему). */
    fun onReject(reason: String, nowMs: Long): List<Effect> {
        if (!outgoing) return emptyList()
        if (phase != Phase.OFFERING && phase != Phase.RINGING && phase != Phase.CONNECTING) {
            return emptyList()
        }
        return endWith(reason, nowMs, stopMedia = phase == Phase.CONNECTING)
    }

    /** Пришёл bye от второй стороны (любая роль). */
    fun onBye(reason: String, nowMs: Long): List<Effect> {
        if (phase == Phase.ENDED || phase == Phase.IDLE) return emptyList()
        val wasHalfOpen = phase == Phase.CONNECTING || phase == Phase.ACTIVE
        val wasIncoming = phase == Phase.INCOMING
        val effects = ArrayList<Effect>(3)
        if (wasHalfOpen) effects.add(Effect.StopMedia)
        if (wasIncoming) effects.add(Effect.CancelIncoming)
        endReason = reason
        moveTo(Phase.ENDED, nowMs)
        return effects
    }

    // ── События от пользователя ─────────────────────────────────────────────

    /** Принимающий нажал «Принять». */
    fun userAccept(nowMs: Long): List<Effect> {
        if (outgoing || phase != Phase.INCOMING) return emptyList()
        moveTo(Phase.CONNECTING, nowMs)
        return listOf(
            Effect.SendAccept,
            Effect.CancelIncoming,
            Effect.StartMedia,
        )
    }

    /** Принимающий нажал «Отклонить». */
    fun userReject(nowMs: Long): List<Effect> {
        if (outgoing || phase != Phase.INCOMING) return emptyList()
        endReason = CallWire.REJECT_DECLINE
        moveTo(Phase.ENDED, nowMs)
        return listOf(
            Effect.SendReject(CallWire.REJECT_DECLINE),
            Effect.CancelIncoming,
        )
    }

    /** Звонящий нажал «Отменить» до соединения. */
    fun userCancel(nowMs: Long): List<Effect> {
        if (!outgoing) return emptyList()
        if (phase != Phase.OFFERING && phase != Phase.RINGING) return emptyList()
        return endWith(CallWire.BYE_CANCEL, nowMs, stopMedia = false, notifyBye = true)
    }

    /** Нажатие «Завершить» в CONNECTING/ACTIVE (любая роль). */
    fun userHangup(nowMs: Long): List<Effect> {
        if (phase != Phase.CONNECTING && phase != Phase.ACTIVE) return emptyList()
        return endWith(CallWire.BYE_END, nowMs, stopMedia = true, notifyBye = true)
    }

    // ── Медиа-события ───────────────────────────────────────────────────────

    /** Первый принятый голосовой кадр: соединение состоялось. */
    fun mediaFrame(nowMs: Long): List<Effect> {
        lastMediaAtMs = nowMs
        recovering = false
        if (phase == Phase.CONNECTING) {
            connectedAtMs = nowMs
            moveTo(Phase.ACTIVE, nowMs)
            return listOf(Effect.MarkMediaUp)
        }
        return emptyList()
    }

    // ── Таймер (менеджер зовёт периодически) ───────────────────────────────

    fun tick(nowMs: Long): List<Effect> {
        when (phase) {
            Phase.OFFERING -> {
                if (nowMs - startedAtMs >= OFFER_TOTAL_TIMEOUT_MS) {
                    return endWith(CallWire.BYE_TIMEOUT, nowMs, stopMedia = false, notifyBye = true)
                }
                if (nowMs - lastOfferAtMs >= OFFER_RETRY_MS) {
                    offerAttempts += 1
                    lastOfferAtMs = nowMs
                    return listOf(Effect.SendOffer(offerAttempts))
                }
            }

            Phase.RINGING ->
                if (nowMs - phaseChangedAtMs >= RING_TIMEOUT_MS) {
                    return endWith(CallWire.BYE_TIMEOUT, nowMs, stopMedia = false, notifyBye = true)
                }

            Phase.INCOMING ->
                if (nowMs - phaseChangedAtMs >= INCOMING_USER_WAIT_MS) {
                    // Протухший звонок гасим молча: звонящий закроется по своему таймауту.
                    endReason = "missed"
                    moveTo(Phase.ENDED, nowMs)
                    return listOf(Effect.CancelIncoming)
                }

            Phase.CONNECTING ->
                if (nowMs - phaseChangedAtMs >= CONNECT_TIMEOUT_MS) {
                    return endWith(CallWire.BYE_FAILED, nowMs, stopMedia = true, notifyBye = true)
                }

            Phase.ACTIVE -> {
                val silenceMs = nowMs - lastMediaAtMs
                if (silenceMs >= ACTIVE_DEATH_SILENCE_MS) {
                    return endWith(CallWire.BYE_FAILED, nowMs, stopMedia = true, notifyBye = true)
                }
                recovering = silenceMs >= ACTIVE_RECOVER_SILENCE_MS
            }

            else -> Unit
        }
        return emptyList()
    }

    // ── Внутреннее ──────────────────────────────────────────────────────────

    private fun endWith(
        reason: String,
        nowMs: Long,
        stopMedia: Boolean,
        notifyBye: Boolean = false,
    ): List<Effect> {
        endReason = reason
        moveTo(Phase.ENDED, nowMs)
        val effects = ArrayList<Effect>(3)
        if (stopMedia) effects.add(Effect.StopMedia)
        if (notifyBye) effects.add(Effect.SendBye(reason, nextByeAttempt()))
        return effects
    }

    private fun nextByeAttempt(): Int {
        byeAttempts += 1
        return byeAttempts
    }

    private fun moveTo(next: Phase, nowMs: Long) {
        if (phase == next) return
        phase = next
        phaseChangedAtMs = nowMs
        if (next == Phase.ACTIVE) recovering = false
    }

    companion object {
        const val OFFER_RETRY_MS = 3_000L
        const val OFFER_TOTAL_TIMEOUT_MS = 30_000L
        const val RING_TIMEOUT_MS = 60_000L
        const val INCOMING_USER_WAIT_MS = 45_000L
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val ACTIVE_RECOVER_SILENCE_MS = 5_000L
        const val ACTIVE_DEATH_SILENCE_MS = 20_000L
    }
}
