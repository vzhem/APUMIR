package com.vladimir.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Политика пробуждения доставки (задача владельца 2026-10-28: «как в
 * Telegram — если убило приложение, оно понимает и просыпается»).
 *
 * Здесь проверяется не Android, а само решение: когда будить, через сколько
 * и не превращается ли «быстрое пробуждение» в круг из 288 будильников в
 * сутки. Ошибка в этих числах не видна в коде, но видна по батарее и по
 * задержке писем, поэтому числа закреплены тестом.
 */
class DeliveryWakePolicyTest {

    private fun minutes(value: Long?): Long? = value?.div(60_000L)

    private val ready = DeliveryWakePolicy.Conditions(
        enabled = true,
        batteryUnrestricted = true,
        exactAlarmsAllowed = true,
        notificationsAllowed = true,
    )

    @Test
    fun `watchdog is quiet and the fast wake is really fast`() {
        assertEquals("сторож — раз в 15 минут", 15L, minutes(DeliveryWakePolicy.WATCHDOG_MS))
        assertEquals("после убийства — через 2 минуты", 2L, minutes(DeliveryWakePolicy.FAST_WAKE_MS))
        assertTrue(
            "быстрый подъём обязан быть быстрее сторожа, иначе «просыпается сам» — неправда",
            DeliveryWakePolicy.FAST_WAKE_MS < DeliveryWakePolicy.WATCHDOG_MS,
        )
    }

    @Test
    fun `retry ladder starts at a minute escalates and caps at half an hour`() {
        val ladder = DeliveryWakePolicy.RETRY_LADDER_MS.map { minutes(it) }
        assertEquals(listOf(1L, 5L, 15L, 30L), ladder)
        assertEquals("ступени обязаны расти", ladder.sorted(), ladder)
        assertEquals("последняя ступень — предел, вечно будить нельзя", 30L, ladder.last())
    }

    @Test
    fun `ladder stays inside its bounds for any number of refusals`() {
        // onServiceRefused считает ступень по числу подряд идущих отказов:
        // и после первого, и после сотого отказа план обязан остаться в пределах.
        assertEquals(1L, minutes(DeliveryWakePolicy.afterRefusal(ready, 1).delayMs))
        assertEquals(30L, minutes(DeliveryWakePolicy.afterRefusal(ready, 100).delayMs))
        val ladder = DeliveryWakePolicy.RETRY_LADDER_MS.toList()
        for (misses in 0..200) {
            val delay = DeliveryWakePolicy.afterRefusal(ready, misses).delayMs
            assertNotNull("отказ №$misses обязан дать план пробуждения", delay)
            assertTrue("ступень №$misses вне лестницы", delay!! in ladder)
        }
    }

    @Test
    fun `service gone and swiped app both wake in two minutes`() {
        assertEquals(2L, minutes(DeliveryWakePolicy.serviceGone(ready).delayMs))
        assertEquals(2L, minutes(DeliveryWakePolicy.taskRemoved(ready).delayMs))
        assertEquals(DeliveryWakePolicy.Reason.SERVICE_GONE, DeliveryWakePolicy.serviceGone(ready).reason)
        assertEquals(DeliveryWakePolicy.Reason.TASK_REMOVED, DeliveryWakePolicy.taskRemoved(ready).reason)
    }

    @Test
    fun `planned stop does not start a two minute loop`() {
        // Служба остановилась сама (нет личности, ядро не поднялось). Причина
        // не исчезнет за две минуты: частый подъём дал бы круг. Но и совсем
        // не будить нельзя — человек остался бы без писем до открытия.
        val plan = DeliveryWakePolicy.afterPlannedStop(ready)
        assertEquals(60L, minutes(plan.delayMs))
        assertEquals(DeliveryWakePolicy.Reason.SERVICE_GONE, plan.reason)
        assertFalse("страховка не бывает точной", plan.exact)
    }

    @Test
    fun `exact alarm is used only when the phone actually allows background work`() {
        assertTrue(DeliveryWakePolicy.canBeExact(ready))
        assertTrue(DeliveryWakePolicy.whileAlive(ready).exact)
        assertFalse(
            "в оптимизации батареи точный будильник обманывает сам себя",
            DeliveryWakePolicy.whileAlive(ready.copy(batteryUnrestricted = false)).exact,
        )
        assertFalse(
            "без разрешения на точные будильники setExact бросит исключение",
            DeliveryWakePolicy.whileAlive(ready.copy(exactAlarmsAllowed = false)).exact,
        )
        assertFalse(
            "выключенная настройка не даёт права на дорогой будильник",
            DeliveryWakePolicy.whileAlive(ready.copy(enabled = false)).exact,
        )
    }

    @Test
    fun `switched off or muted means no wake at all`() {
        val off = DeliveryWakePolicy.whileAlive(ready.copy(enabled = false))
        assertNull(off.delayMs)
        assertEquals(DeliveryWakePolicy.Reason.DISABLED, off.reason)
        val muted = DeliveryWakePolicy.serviceGone(ready.copy(notificationsAllowed = false))
        assertNull(muted.delayMs)
        assertEquals(DeliveryWakePolicy.Reason.NO_NOTIFICATIONS, muted.reason)
    }

    @Test
    fun `watchdog wakes only when the service looks dead`() {
        val now = 1_000_000_000L
        assertTrue("первый запуск: метки нет — поднимаем", DeliveryWakePolicy.shouldWakeNow(ready, 0L, now))
        assertFalse(
            "служба только что отметилась — второй экземпляр не нужен",
            DeliveryWakePolicy.shouldWakeNow(ready, now - 60_000L, now),
        )
        assertFalse(
            "одно пропущенное пробуждение — ещё не смерть",
            DeliveryWakePolicy.shouldWakeNow(
                ready,
                now - DeliveryWakePolicy.WATCHDOG_MS,
                now,
            ),
        )
        assertTrue(
            "метка старше порога — служба мертва, поднимаем",
            DeliveryWakePolicy.shouldWakeNow(ready, now - DeliveryWakePolicy.STALE_AFTER_MS, now),
        )
        assertTrue(
            "часы перевели назад — считаем метку старой, иначе не будили бы никогда",
            DeliveryWakePolicy.shouldWakeNow(ready, now + 5 * 60_000L, now),
        )
        assertFalse(
            "человек выключил доставку — не будим",
            DeliveryWakePolicy.shouldWakeNow(ready.copy(enabled = false), 0L, now),
        )
        assertFalse(
            "уведомления запрещены — будить нечего",
            DeliveryWakePolicy.shouldWakeNow(ready.copy(notificationsAllowed = false), 0L, now),
        )
    }

    @Test
    fun `staleness threshold tolerates three missed watchdogs`() {
        assertEquals(45L, minutes(DeliveryWakePolicy.STALE_AFTER_MS))
        assertTrue(
            "порог обязан быть выше интервала сторожа, иначе каждый тик — пробуждение",
            DeliveryWakePolicy.STALE_AFTER_MS > DeliveryWakePolicy.WATCHDOG_MS,
        )
    }
}
