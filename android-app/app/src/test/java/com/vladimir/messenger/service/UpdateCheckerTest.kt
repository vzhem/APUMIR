package com.vladimir.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `compares numeric version components instead of strings`() {
        assertTrue(isVersionNewer("v11.9", "v11.16"))
        assertFalse(isVersionNewer("v11.16", "v11.9"))
    }

    @Test
    fun `supports optional patch components`() {
        assertTrue(isVersionNewer("v11.16", "v11.16.2"))
        assertFalse(isVersionNewer("v11.16.2", "v11.16"))
        assertFalse(isVersionNewer("v11.16.0", "v11.16"))
    }

    @Test
    fun `ignores prerelease suffix while comparing numeric components`() {
        assertTrue(isVersionNewer("v11.16.1-beta", "v11.16.2"))
        assertFalse(isVersionNewer("v11.16.2", "v11.16.2+build.4"))
    }

    @Test
    fun `tries relay fallback when GitHub is unavailable or malformed`() {
        assertTrue(shouldTryRelayFallback("network_error"))
        assertTrue(shouldTryRelayFallback("http_error"))
        assertTrue(shouldTryRelayFallback("invalid_response"))
        assertTrue(shouldTryRelayFallback("asset_missing"))
        assertFalse(shouldTryRelayFallback("up_to_date"))
        assertFalse(shouldTryRelayFallback("update_available"))
    }

    @Test
    fun `maps endpoint failures separately from a confirmed up to date result`() {
        assertEquals(
            "up_to_date",
            resolveUpdateCheckOutcome(false, "up_to_date", "not_attempted"),
        )
        assertEquals(
            "up_to_date",
            resolveUpdateCheckOutcome(false, "http_error", "up_to_date"),
        )
        assertEquals(
            "unavailable",
            resolveUpdateCheckOutcome(false, "asset_missing", "up_to_date"),
        )
        assertEquals(
            "fallback_not_due",
            resolveUpdateCheckOutcome(false, "network_error", "not_due"),
        )
        assertEquals(
            "unavailable",
            resolveUpdateCheckOutcome(false, "http_error", "network_error"),
        )
        assertEquals(
            "update_available",
            resolveUpdateCheckOutcome(true, "http_error", "not_attempted"),
        )
    }

    @Test
    fun `manual window is bounded and a second tap never shortens it`() {
        val window = 30L * 60_000L
        val opened = manualExternalWindowUntil(1_000L, 0L, window)
        assertEquals(1_000L + window, opened)
        assertTrue(manualExternalWindowActive(opened - 1, opened))
        assertFalse(manualExternalWindowActive(opened, opened)) // На границе окно уже закрыто.
        assertEquals(opened, manualExternalWindowUntil(2_000L, opened, 1_000L))
        assertEquals(2_000L + window, manualExternalWindowUntil(2_000L, opened, window))
    }

    @Test
    fun `release download is allowed by the core policy or by the manual window`() {
        assertFalse(externalReleaseAllowed(transportAllowed = false, manualWindowActive = false))
        assertTrue(externalReleaseAllowed(transportAllowed = true, manualWindowActive = false))
        assertTrue(externalReleaseAllowed(transportAllowed = false, manualWindowActive = true))
    }

    @Test
    fun `swarm first outcomes reach the log instead of turning into unknown`() {
        // Раньше эти два исхода не были в списке и в «Логах» выглядели как
        // unknown: по отчёту нельзя было понять, что проверку остановил рой.
        assertEquals("swarm_first_no_http", UpdateDiagnosticsStore.safeOutcome("swarm_first_no_http"))
        assertEquals("swarm_search_pending", UpdateDiagnosticsStore.safeOutcome("swarm_search_pending"))
        assertEquals("up_to_date", UpdateDiagnosticsStore.safeOutcome("up_to_date"))
        assertEquals("swarm_available", UpdateDiagnosticsStore.safeOutcome("swarm_available"))
        assertEquals("unknown", UpdateDiagnosticsStore.safeOutcome("своё-слово"))
        assertEquals("not_manual", UpdateDiagnosticsStore.safeManualWindow("not_manual"))
        assertEquals("not_attempted", UpdateDiagnosticsStore.safeManualWindow("not_attempted"))
        assertEquals("already_allowed", UpdateDiagnosticsStore.safeManualWindow("already_allowed"))
        assertEquals("opened", UpdateDiagnosticsStore.safeManualWindow("opened"))
        assertEquals("unknown", UpdateDiagnosticsStore.safeManualWindow("opened-by-hand"))
    }

    @Test
    fun `rejects malformed versions`() {
        assertFalse(isVersionNewer("development", "v11.16.2"))
        assertFalse(isVersionNewer("v11.16", "latest"))
    }
}
