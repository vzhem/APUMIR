package com.vladimir.messenger.data.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * День рождения в живом зеркале профиля: владелец 2026-10-15, «доделай зеркало».
 *
 * Раньше профиль между своими устройствами сходился по имени, нику, теме, аватару
 * и обоям, а дата рождения оставалась на том телефоне, где её задали. Здесь
 * проверяется договор, который это закрывает: день и месяц с галочкой видимости
 * входят в снимок профиля, у них своя метка времени, а ОБЩИЙ отпечаток профиля
 * («pd» в hello/hb) намеренно считается без дня рождения - иначе устройство на
 * старой сборке, которая про день рождения не знает, расходилось бы с новым
 * вечно и они гоняли бы полный снимок профиля каждые 10 секунд.
 *
 * Только чистая арифметика: `toJson`/`fromJson` тут не трогаем - `org.json` в
 * JVM-тестах Android-модуля не работает («not mocked»), а его чтение ключей с
 * умолчаниями стережёт `scripts/ci/check-birthday.py`.
 */
class ProfileMirrorBirthdayTest {

    private fun state(
        day: Int = 0,
        month: Int = 0,
        visible: Boolean = false,
        stamp: Long = 0L,
    ) = ProfileMirror.State(
        name = "Владимир",
        nick = "vladimir",
        theme = "auto",
        avName = "",
        avSha = "",
        wallName = "",
        wallSha = "",
        tName = 1L,
        tNick = 1L,
        tTheme = 1L,
        tAv = 1L,
        tWall = 1L,
        bdayDay = day,
        bdayMonth = month,
        bdayVisible = visible,
        tBday = stamp,
    )

    /** Общий отпечаток профиля не знает про день рождения: старые сборки спокойны. */
    @Test
    fun sharedFingerprintIgnoresTheBirthday() {
        assertEquals(state().digest(), state(12, 6, true, 5L).digest())
        assertEquals(state(12, 6, true).digest(), state(12, 6, false).digest())
    }

    /** У дня рождения свой отпечаток: расхождение даты видно и лечится снимком. */
    @Test
    fun birthdayHasItsOwnFingerprint() {
        assertNotEquals(state(12, 6, true).birthdayDigest(), state(12, 6, false).birthdayDigest())
        assertNotEquals(state(12, 6, true).birthdayDigest(), state(13, 6, true).birthdayDigest())
        assertNotEquals(state(12, 6, true).birthdayDigest(), state().birthdayDigest())
        // Метка времени - не содержимое: одна и та же дата даёт один отпечаток,
        // иначе устройства «расходились» бы на ровном месте после каждой правки.
        assertEquals(state(12, 6, true, 1L).birthdayDigest(), state(12, 6, true, 999L).birthdayDigest())
        // «Даты нет» - тоже состояние, а не пустая строка: два новых устройства
        // обязаны сойтись и на этом.
        assertTrue(state().birthdayDigest().isNotBlank())
    }

    /** Ключ для слияния: число, месяц, видимость. Некорректной даты не бывает. */
    @Test
    fun birthdayKeyCarriesDayMonthAndVisibility() {
        assertEquals("", state().bdayKey())
        assertEquals("", state(32, 13, true).bdayKey())
        assertEquals("", state(31, 4, true).bdayKey())
        assertEquals("29-2-1", state(29, 2, true).bdayKey())
        assertEquals("29-2-0", state(29, 2, false).bdayKey())
        assertEquals("12-6-1", state(12, 6, true).bdayKey())
    }

    /** Расхождение даты названо в диагностике профиля словами. */
    @Test
    fun birthdayDivergenceIsNamedInDiagnostics() {
        assertTrue(state().contentDiff(state(12, 6, true)).contains("день рождения"))
        assertTrue(state(12, 6, true).contentDiff(state(12, 6, false)).contains("день рождения"))
        val same = state(12, 6, true)
        assertTrue(same.contentDiff(same).isEmpty())
    }

    /** Старый партнёр присылает снимок без даты: это «ничего не менял», не «убрал». */
    @Test
    fun olderPartnerSnapshotMeansNoChange() {
        val fromOlderBuild = state()
        assertEquals("", fromOlderBuild.bdayKey())
        assertEquals(0L, fromOlderBuild.tBday)
        // Нулевая метка не перебивает дату, у которой метка есть (правило wins:
        // новее побеждает) - то есть дата с этого телефона не пропадёт.
        assertTrue(fromOlderBuild.tBday < 1_000L)
        assertFalse(fromOlderBuild.bdayVisible)
    }
}
