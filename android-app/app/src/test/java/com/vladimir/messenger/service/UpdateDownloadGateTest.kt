package com.vladimir.messenger.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Замок кнопки «Скачать обновление». Владелец 2026-10-29: «люди жмут кнопку и
 * у них качается по два-три обновления».
 */
class UpdateDownloadGateTest {

    private val version = "v11.74.465"

    @Before
    fun setUp() = UpdateDownloadGate.reset()

    @After
    fun tearDown() = UpdateDownloadGate.reset()

    @Test
    fun `first tap starts the download`() {
        assertEquals(UpdateDownloadGate.Decision.Start, UpdateDownloadGate.request(version, 0L))
    }

    @Test
    fun `second tap while the download is running does not start another one`() {
        UpdateDownloadGate.request(version, 0L)
        val second = UpdateDownloadGate.request(version, 2_000L)
        assertTrue(second is UpdateDownloadGate.Decision.InFlight)
    }

    @Test
    fun `taps in a row never produce more than one download`() {
        var starts = 0
        for (tap in 0 until 10) {
            val now = tap * 400L
            if (UpdateDownloadGate.request(version, now) == UpdateDownloadGate.Decision.Start) starts++
        }
        assertEquals(1, starts)
    }

    @Test
    fun `the button stays silent for thirty seconds after the tap`() {
        UpdateDownloadGate.request(version, 0L)
        UpdateDownloadGate.finished(version)
        val afterFive = UpdateDownloadGate.request(version, 5_000L)
        assertTrue(afterFive is UpdateDownloadGate.Decision.Cooldown)
        assertEquals(
            25_000L,
            (afterFive as UpdateDownloadGate.Decision.Cooldown).remainingMs,
        )
        assertEquals(
            UpdateDownloadGate.Decision.Start,
            UpdateDownloadGate.request(version, UpdateDownloadGate.COOLDOWN_MS),
        )
    }

    @Test
    fun `a download manager download survives a process restart`() {
        // Приложение перезапустили: таймер пуст, но файл уже качается.
        UpdateDownloadGate.reset()
        val decision = UpdateDownloadGate.request(version, 0L, hasActiveDownload = true)
        assertTrue(decision is UpdateDownloadGate.Decision.InFlight)
    }

    @Test
    fun `another version is a new decision, not a block`() {
        UpdateDownloadGate.request(version, 0L)
        assertEquals(
            UpdateDownloadGate.Decision.Start,
            UpdateDownloadGate.request("v11.74.466", 1_000L),
        )
    }

    @Test
    fun `a forgotten flight mark expires instead of locking the button forever`() {
        UpdateDownloadGate.request(version, 0L)
        val late = UpdateDownloadGate.request(version, UpdateDownloadGate.IN_FLIGHT_TTL_MS + 1)
        assertEquals(UpdateDownloadGate.Decision.Start, late)
    }

    @Test
    fun `lost download frees the gate immediately`() {
        UpdateDownloadGate.request(version, 0L)
        UpdateDownloadGate.lost(version)
        assertEquals(UpdateDownloadGate.Decision.Start, UpdateDownloadGate.request(version, 1_000L))
    }

    @Test
    fun `the gate explains itself for diagnostics`() {
        assertTrue(UpdateDownloadGate.describe().contains("свободен"))
        UpdateDownloadGate.request(version, 0L)
        assertTrue(UpdateDownloadGate.describe().contains("идёт загрузка"))
        UpdateDownloadGate.finished(version)
        assertTrue(UpdateDownloadGate.describe().contains("пауза"))
    }
}
