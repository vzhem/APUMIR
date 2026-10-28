package com.vladimir.messenger.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Приёмник пробуждения объявлен `exported="true"` (иначе на части прошивок не
 * доходит BOOT_COMPLETED). Значит, Intent ему может прислать любое приложение
 * — и единственная защита от «чужой будит мессенджер по кругу» это allowlist.
 *
 * Тест фиксирует allowlist поимённо: случайно расширить его (например,
 * принимая любое действие) — значит открыть кнопку «разбуди чужой телефон».
 */
class EmergencyKeepAliveReceiverContractTest {

    @Test
    fun `own actions are accepted`() {
        assertTrue(EmergencyKeepAliveReceiver.isOwnAction(EmergencyKeepAlive.ACTION_PUMP))
        assertTrue(EmergencyKeepAliveReceiver.isOwnAction("android.intent.action.BOOT_COMPLETED"))
        assertTrue(EmergencyKeepAliveReceiver.isOwnAction("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test
    fun `foreign actions are rejected`() {
        assertFalse(EmergencyKeepAliveReceiver.isOwnAction(null))
        assertFalse(EmergencyKeepAliveReceiver.isOwnAction(""))
        assertFalse(EmergencyKeepAliveReceiver.isOwnAction("android.intent.action.SCREEN_ON"))
        assertFalse(EmergencyKeepAliveReceiver.isOwnAction("com.example.WAKE_UP_EVERYTHING"))
        // До первой разблокировки телефон закрывает личность и настройки:
        // принимать этот сигнал нельзя, иначе чтение настроек упало бы.
        assertFalse(EmergencyKeepAliveReceiver.isOwnAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
    }
}
