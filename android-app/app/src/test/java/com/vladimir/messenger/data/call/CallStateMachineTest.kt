package com.vladimir.messenger.data.call

import com.vladimir.messenger.data.call.CallStateMachine.Effect
import com.vladimir.messenger.data.call.CallStateMachine.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {

    private val callId = "0123456789abcdef0123456789abcdef"
    private val peer = "pk_dee60d8c7064cb49b390f7ee4b22aebd"
    private val t0 = 1_000_000L

    private fun caller() = CallStateMachine(callId, peer, outgoing = true, startedAtMs = t0)
    private fun callee() = CallStateMachine(callId, peer, outgoing = false, startedAtMs = t0)

    // ── Счастливый путь звонящего ───────────────────────────────────────────

    @Test
    fun callerHappyPath() {
        val sm = caller()
        assertEquals(Phase.OFFERING, sm.phase)

        // Первый тик сразу шлёт offer.
        val first = sm.tick(t0 + 1)
        assertTrue(first.contains(Effect.SendOffer(1)))

        // Повторы каждые 3 секунды.
        val again = sm.tick(t0 + CallStateMachine.OFFER_RETRY_MS + 1)
        assertTrue(again.contains(Effect.SendOffer(2)))

        sm.onRing(t0 + 4000)
        assertEquals(Phase.RINGING, sm.phase)

        val accept = sm.onAccept(t0 + 6000)
        assertEquals(Phase.CONNECTING, sm.phase)
        assertTrue(accept.contains(Effect.StartMedia))

        sm.mediaFrame(t0 + 7000)
        assertEquals(Phase.ACTIVE, sm.phase)
        assertEquals(t0 + 7000, sm.connectedAtMs)

        val hangup = sm.userHangup(t0 + 37_000)
        assertEquals(Phase.ENDED, sm.phase)
        assertTrue(hangup.any { it is Effect.StopMedia })
        assertTrue(hangup.contains(Effect.SendBye(CallWire.BYE_END, 1)))
    }

    // ── Счастливый путь принимающего ────────────────────────────────────────

    @Test
    fun calleeHappyPath() {
        val sm = callee()
        assertEquals(Phase.INCOMING, sm.phase)

        val accept = sm.userAccept(t0 + 2000)
        assertEquals(Phase.CONNECTING, sm.phase)
        assertTrue(accept.contains(Effect.SendAccept))
        assertTrue(accept.contains(Effect.StartMedia))
        assertTrue(accept.contains(Effect.CancelIncoming))

        sm.mediaFrame(t0 + 4000)
        assertEquals(Phase.ACTIVE, sm.phase)
    }

    @Test
    fun calleeRejectSendsDecline() {
        val sm = callee()
        val effects = sm.userReject(t0 + 1000)
        assertEquals(Phase.ENDED, sm.phase)
        assertEquals(CallWire.REJECT_DECLINE, sm.endReason)
        assertTrue(effects.contains(Effect.SendReject(CallWire.REJECT_DECLINE)))
        assertTrue(effects.contains(Effect.CancelIncoming))
    }

    // ── Отказы и завершения ─────────────────────────────────────────────────

    @Test
    fun callerReceivesBusyReject() {
        val sm = caller()
        sm.tick(t0 + 1) // offer #1
        val effects = sm.onReject(CallWire.REJECT_BUSY, t0 + 1500)
        assertEquals(Phase.ENDED, sm.phase)
        assertEquals(CallWire.REJECT_BUSY, sm.endReason)
        assertTrue(effects.none { it is Effect.SendBye }) // на reject не отвечаем bye
    }

    @Test
    fun callerCancelBeforeAcceptSendsBye() {
        val sm = caller()
        sm.tick(t0 + 1)
        val effects = sm.userCancel(t0 + 500)
        assertEquals(Phase.ENDED, sm.phase)
        assertEquals(CallWire.BYE_CANCEL, sm.endReason)
        assertTrue(effects.contains(Effect.SendBye(CallWire.BYE_CANCEL, 1)))
        assertFalse(effects.any { it is Effect.StopMedia }) // звука ещё не было
    }

    @Test
    fun incomingByeDuringActiveStopsMedia() {
        val sm = callee()
        sm.userAccept(t0 + 1000)
        sm.mediaFrame(t0 + 2000)
        val effects = sm.onBye(CallWire.BYE_END, t0 + 9000)
        assertEquals(Phase.ENDED, sm.phase)
        assertTrue(effects.contains(Effect.StopMedia))
    }

    // ── Таймауты ────────────────────────────────────────────────────────────

    @Test
    fun offerDiesAfterTotalTimeout() {
        val sm = caller()
        sm.tick(t0 + 1)
        val effects = sm.tick(t0 + CallStateMachine.OFFER_TOTAL_TIMEOUT_MS + 1)
        assertEquals(Phase.ENDED, sm.phase)
        assertEquals(CallWire.BYE_TIMEOUT, sm.endReason)
        assertTrue(effects.contains(Effect.SendBye(CallWire.BYE_TIMEOUT, 1)))
    }

    @Test
    fun ringingDiesAfterTimeout() {
        val sm = caller()
        sm.tick(t0 + 1)
        sm.onRing(t0 + 1000)
        val effects = sm.tick(t0 + 1000 + CallStateMachine.RING_TIMEOUT_MS)
        assertEquals(Phase.ENDED, sm.phase)
        assertEquals(CallWire.BYE_TIMEOUT, sm.endReason)
        assertTrue(effects.any { it is Effect.SendBye })
    }

    @Test
    fun incomingSilenceEndsLocallyAsMissed() {
        val sm = callee()
        val effects = sm.tick(t0 + CallStateMachine.INCOMING_USER_WAIT_MS + 1)
        assertEquals(Phase.ENDED, sm.phase)
        assertEquals("missed", sm.endReason)
        assertTrue(effects.contains(Effect.CancelIncoming))
        // Уходящего сигнала нет: звонящий закроется по своему таймауту.
        assertTrue(effects.none { it is Effect.SendReject || it is Effect.SendBye })
    }

    @Test
    fun connectingDiesWithoutMedia() {
        val sm = callee()
        sm.userAccept(t0 + 500)
        val effects = sm.tick(t0 + 500 + CallStateMachine.CONNECT_TIMEOUT_MS)
        assertEquals(Phase.ENDED, sm.phase)
        assertEquals(CallWire.BYE_FAILED, sm.endReason)
        assertTrue(effects.contains(Effect.StopMedia))
        assertTrue(effects.any { it is Effect.SendBye })
    }

    @Test
    fun activeRecoversThenDiesOnSilence() {
        val sm = callee()
        sm.userAccept(t0)
        sm.mediaFrame(t0 + 1000)
        assertEquals(Phase.ACTIVE, sm.phase)

        // 5+ секунд тишины → восстановление, ещё не смерть.
        sm.tick(t0 + 1000 + CallStateMachine.ACTIVE_RECOVER_SILENCE_MS + 1)
        assertTrue(sm.recovering)
        assertEquals(Phase.ACTIVE, sm.phase)

        // Кадр до 20 секунд тишины спасает звонок.
        sm.mediaFrame(t0 + 1000 + 10_000)
        assertFalse(sm.recovering)

        // Полные 20 секунд тишины → failed.
        val death = sm.tick(t0 + 1000 + 10_000 + CallStateMachine.ACTIVE_DEATH_SILENCE_MS)
        assertEquals(Phase.ENDED, sm.phase)
        assertEquals(CallWire.BYE_FAILED, sm.endReason)
        assertTrue(death.any { it is Effect.SendBye })
    }

    // ── Сторож темпа кадров (приёмка 2026-09-01: зомби-звонок при смене сети) ──

    @Test
    fun activeDiesOnFrameStarvationNotJustSilence() {
        val sm = callee()
        sm.userAccept(t0)
        sm.mediaFrame(t0 + 1000) // ACTIVE
        // Умирающий канал: одинокие кадры каплями раз в 4 с — полной тишины
        // (20 с подряд) никогда нет, но и жизни нет. Звонок обязан закрыться.
        var t = t0 + 1000
        var ended = false
        repeat(20) {
            t += 4000
            sm.mediaFrame(t)
            sm.tick(t)
            if (sm.phase == Phase.ENDED) ended = true
        }
        assertTrue(ended)
        assertEquals(CallWire.BYE_FAILED, sm.endReason)
    }

    @Test
    fun activeSurvivesHealthyFrameRate() {
        val sm = callee()
        sm.userAccept(t0)
        sm.mediaFrame(t0 + 1000)
        var t = t0 + 1000
        // Здоровый темп ~60 кадров/с на протяжении 20 с: живём, не в «восстановлении».
        repeat(40) {
            t += 500
            repeat(30) { sm.mediaFrame(t) }
            sm.tick(t)
        }
        assertEquals(Phase.ACTIVE, sm.phase)
        assertFalse(sm.recovering)
    }

    // ── Защита от мусора ────────────────────────────────────────────────────

    @Test
    fun stalePacketsAreIgnored() {
        val sm = caller()
        sm.tick(t0 + 1)
        sm.onReject(CallWire.REJECT_DECLINE, t0 + 1500)
        assertEquals(Phase.ENDED, sm.phase)

        // Поздние кадры/bye/accept после конца ничего не меняют.
        assertTrue(sm.onAccept(t0 + 2000).isEmpty())
        assertTrue(sm.onBye(CallWire.BYE_END, t0 + 2100).isEmpty())
        assertTrue(sm.mediaFrame(t0 + 2200).isEmpty())
        assertEquals(Phase.ENDED, sm.phase)
    }

    @Test
    fun wrongRoleEventsIgnored() {
        val sm = callee()
        assertTrue(sm.onAccept(t0).isEmpty()) // accept самому себе
        assertTrue(sm.onRing(t0).isEmpty())   // ring самому себе
        assertTrue(sm.userCancel(t0).isEmpty()) // отмена на принимающем не существует
    }
}
