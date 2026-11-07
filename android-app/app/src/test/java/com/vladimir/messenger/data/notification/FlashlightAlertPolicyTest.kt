package com.vladimir.messenger.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashlightAlertPolicyTest {

    @Test
    fun defaultsBlinkOnGroupSosAndStayOffForRegularMessages() {
        val defaults = FlashlightAlertConfig()
        assertTrue(defaults.sosEnabled)
        assertEquals(FlashlightMessageMode.OFF, defaults.messageMode)
        assertTrue(FlashlightAlertPolicy.shouldBlinkForSos(defaults, isGroup = true))
        assertFalse(FlashlightAlertPolicy.shouldBlinkForSos(defaults, isGroup = false))
        for (kind in FlashlightMessageKind.entries) {
            assertFalse(
                FlashlightAlertPolicy.shouldBlinkForMessage(
                    config = defaults,
                    kind = kind,
                    silent = false,
                    muted = false,
                ),
            )
        }
    }

    @Test
    fun recipientCanTurnSosFlashlightOffAndBackOn() {
        val off = FlashlightAlertConfig(sosEnabled = false, messageMode = FlashlightMessageMode.ALL)
        assertFalse(FlashlightAlertPolicy.shouldBlinkForSos(off, isGroup = true))
        assertEquals(
            BlinkDecision.NONE,
            FlashlightAlertPolicy.decideSos(off, isGroup = true, active = null, nowMs = 1_000L),
        )

        val on = off.copy(sosEnabled = true)
        assertTrue(FlashlightAlertPolicy.shouldBlinkForSos(on, isGroup = true))
        assertEquals(
            BlinkDecision.START_SOS,
            FlashlightAlertPolicy.decideSos(on, isGroup = true, active = null, nowMs = 1_000L),
        )
    }

    @Test
    fun classifySeparatesPersonalGroupsAndChannels() {
        assertEquals(
            FlashlightMessageKind.PERSONAL,
            FlashlightMessageKind.classify(hasGroup = false, isChannel = false),
        )
        assertEquals(
            FlashlightMessageKind.PERSONAL,
            FlashlightMessageKind.classify(hasGroup = false, isChannel = true),
        )
        assertEquals(
            FlashlightMessageKind.GROUP,
            FlashlightMessageKind.classify(hasGroup = true, isChannel = false),
        )
        assertEquals(
            FlashlightMessageKind.CHANNEL,
            FlashlightMessageKind.classify(hasGroup = true, isChannel = true),
        )
    }

    @Test
    fun messageModesMatchAllPersonalGroupsAndChannels() {
        val all = FlashlightAlertConfig(messageMode = FlashlightMessageMode.ALL)
        for (kind in FlashlightMessageKind.entries) {
            assertTrue(FlashlightAlertPolicy.shouldBlinkForMessage(all, kind, silent = false, muted = false))
        }

        val personalOnly = FlashlightAlertConfig(messageMode = FlashlightMessageMode.PERSONAL)
        assertTrue(FlashlightAlertPolicy.shouldBlinkForMessage(personalOnly, FlashlightMessageKind.PERSONAL, silent = false, muted = false))
        assertFalse(FlashlightAlertPolicy.shouldBlinkForMessage(personalOnly, FlashlightMessageKind.GROUP, silent = false, muted = false))
        assertFalse(FlashlightAlertPolicy.shouldBlinkForMessage(personalOnly, FlashlightMessageKind.CHANNEL, silent = false, muted = false))

        val groupsOnly = FlashlightAlertConfig(messageMode = FlashlightMessageMode.GROUPS)
        assertFalse(FlashlightAlertPolicy.shouldBlinkForMessage(groupsOnly, FlashlightMessageKind.PERSONAL, silent = false, muted = false))
        assertTrue(FlashlightAlertPolicy.shouldBlinkForMessage(groupsOnly, FlashlightMessageKind.GROUP, silent = false, muted = false))
        assertFalse(FlashlightAlertPolicy.shouldBlinkForMessage(groupsOnly, FlashlightMessageKind.CHANNEL, silent = false, muted = false))

        val channelsOnly = FlashlightAlertConfig(messageMode = FlashlightMessageMode.CHANNELS)
        assertFalse(FlashlightAlertPolicy.shouldBlinkForMessage(channelsOnly, FlashlightMessageKind.PERSONAL, silent = false, muted = false))
        assertFalse(FlashlightAlertPolicy.shouldBlinkForMessage(channelsOnly, FlashlightMessageKind.GROUP, silent = false, muted = false))
        assertTrue(FlashlightAlertPolicy.shouldBlinkForMessage(channelsOnly, FlashlightMessageKind.CHANNEL, silent = false, muted = false))
    }

    @Test
    fun silentAndMutedRegularMessagesNeverBlinkEvenInAllMode() {
        val all = FlashlightAlertConfig(messageMode = FlashlightMessageMode.ALL)
        for (kind in FlashlightMessageKind.entries) {
            assertFalse(FlashlightAlertPolicy.shouldBlinkForMessage(all, kind, silent = true, muted = false))
            assertFalse(FlashlightAlertPolicy.shouldBlinkForMessage(all, kind, silent = false, muted = true))
            assertEquals(
                BlinkDecision.NONE,
                FlashlightAlertPolicy.decideMessage(all, kind, silent = true, muted = false, active = null, nowMs = 500L),
            )
        }
    }

    @Test
    fun unknownModeFailsSafelyToOff() {
        assertEquals(FlashlightMessageMode.OFF, FlashlightMessageMode.fromId(null))
        assertEquals(FlashlightMessageMode.OFF, FlashlightMessageMode.fromId(""))
        assertEquals(FlashlightMessageMode.OFF, FlashlightMessageMode.fromId("unknown_mode"))
        for (mode in FlashlightMessageMode.entries) {
            assertEquals(mode, FlashlightMessageMode.fromId(mode.id))
        }
    }

    @Test
    fun sosBurstOfFiveMessagesCoalescesIntoOnePatternAndAllowsNextPressAfterCooldown() {
        val config = FlashlightAlertConfig(sosEnabled = true)
        val t0 = 10_000L
        assertEquals(
            BlinkDecision.START_SOS,
            FlashlightAlertPolicy.decideSos(config, isGroup = true, active = null, nowMs = t0),
        )
        val activeSos = ActiveBlinkState(ActiveBlinkKind.SOS, startedAtMs = t0)
        // Остальные 4 сообщения одного нажатия приходят следом и не сбивают ритм.
        for (offset in listOf(10L, 120L, 800L, 3_500L)) {
            assertEquals(
                BlinkDecision.COALESCED,
                FlashlightAlertPolicy.decideSos(config, isGroup = true, active = activeSos, nowMs = t0 + offset),
            )
        }
        // Следующее настоящее нажатие (после 10-секундной паузы кнопки SOS) запускает серию заново.
        assertEquals(
            BlinkDecision.START_SOS,
            FlashlightAlertPolicy.decideSos(config, isGroup = true, active = activeSos, nowMs = t0 + 10_000L),
        )
    }

    @Test
    fun sosPreemptsRegularMessageBlinkWhileRegularMessagesNeverInterruptSos() {
        val config = FlashlightAlertConfig(sosEnabled = true, messageMode = FlashlightMessageMode.ALL)
        val activeMessage = ActiveBlinkState(ActiveBlinkKind.MESSAGE, startedAtMs = 2_000L)
        assertEquals(
            BlinkDecision.START_SOS,
            FlashlightAlertPolicy.decideSos(config, isGroup = true, active = activeMessage, nowMs = 2_050L),
        )

        val activeSos = ActiveBlinkState(ActiveBlinkKind.SOS, startedAtMs = 2_000L)
        assertEquals(
            BlinkDecision.COALESCED,
            FlashlightAlertPolicy.decideMessage(
                config = config,
                kind = FlashlightMessageKind.PERSONAL,
                silent = false,
                muted = false,
                active = activeSos,
                nowMs = 2_500L,
            ),
        )
        // Обычные сообщения подряд тоже склеиваются в пределах короткого окна.
        assertEquals(
            BlinkDecision.COALESCED,
            FlashlightAlertPolicy.decideMessage(
                config = config,
                kind = FlashlightMessageKind.PERSONAL,
                silent = false,
                muted = false,
                active = activeMessage,
                nowMs = 2_400L,
            ),
        )
        assertEquals(
            BlinkDecision.START_MESSAGE,
            FlashlightAlertPolicy.decideMessage(
                config = config,
                kind = FlashlightMessageKind.PERSONAL,
                silent = false,
                muted = false,
                active = activeMessage,
                nowMs = 2_000L + FlashlightAlertPolicy.MESSAGE_COOLDOWN_MS,
            ),
        )
    }

    @Test
    fun patternsStartAndEndOnActivePulseAndFitInsideWakeLockBounds() {
        val sos = FlashlightAlertPolicy.SOS_PATTERN_MS
        assertEquals(17, sos.size)
        assertEquals(1, sos.size % 2)
        assertTrue(sos.all { it > 0L })
        assertTrue(sos.sum() < FlashlightAlertPolicy.SOS_WAKE_LOCK_MS)

        val msg = FlashlightAlertPolicy.MESSAGE_PATTERN_MS
        assertEquals(3, msg.size)
        assertEquals(1, msg.size % 2)
        assertTrue(msg.all { it > 0L })
        assertTrue(msg.sum() < FlashlightAlertPolicy.MESSAGE_WAKE_LOCK_MS)
    }
}
