package com.vladimir.messenger.data.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Владелец 2026-11-18: «пишешь сообщение, проходит пара секунд и происходит
 * моргание». Плашка-уведомление появлялась поверх открытого чата, потому что
 * приложение не знало, какой чат человек читает.
 *
 * Проверяем три правила и одно «неизвестно = показывать»: тишина допустима
 * только когда сообщение действительно видно на экране.
 */
class OpenChatGateTest {

    @Test fun anOpenChatInForegroundIsSilent() {
        OpenChatGate.opened("chat-a")
        assertTrue(OpenChatGate.isReading("chat-a", appInForeground = true))
    }

    @Test fun anotherChatStillNotifies() {
        OpenChatGate.opened("chat-a")
        assertFalse(OpenChatGate.isReading("chat-b", appInForeground = true))
    }

    @Test fun aChatOpenInBackgroundStillNotifies() {
        // Свёрнутое приложение ничего не показывает: молчать здесь — потерять
        // входящее.
        OpenChatGate.opened("chat-a")
        assertFalse(OpenChatGate.isReading("chat-a", appInForeground = false))
    }

    @Test fun closingAnotherChatKeepsTheOpenOne() {
        OpenChatGate.opened("chat-a")
        OpenChatGate.closed("chat-b")
        assertTrue(OpenChatGate.isReading("chat-a", appInForeground = true))
        OpenChatGate.closed("chat-a")
        assertFalse(OpenChatGate.isReading("chat-a", appInForeground = true))
    }
}
