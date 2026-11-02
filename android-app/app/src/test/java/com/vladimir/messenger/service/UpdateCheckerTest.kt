package com.vladimir.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `uCoz APK is selected only when sidecar version and release digest match`() {
        val releaseSha = "8d81ddc7646b81131bba6072182eb62f6977643aa3008e22d6e7e995798f9706"
        assertTrue(
            siteApkMatchesLatest(
                siteVersion = "v11.74.477",
                latestVersion = "11.74.477",
                siteSha256 = "SHA256:$releaseSha".uppercase(),
                latestSha256 = "sha256:$releaseSha",
            ),
        )
        assertFalse(
            siteApkMatchesLatest(
                siteVersion = "v11.74.476",
                latestVersion = "v11.74.477",
                siteSha256 = releaseSha,
                latestSha256 = releaseSha,
            ),
        )
        assertFalse(
            siteApkMatchesLatest(
                siteVersion = "v11.74.477",
                latestVersion = "v11.74.477",
                siteSha256 = "0".repeat(64),
                latestSha256 = releaseSha,
            ),
        )
        assertFalse(
            siteApkMatchesLatest(
                siteVersion = "v11.74.477",
                latestVersion = "v11.74.477",
                siteSha256 = null,
                latestSha256 = releaseSha,
            ),
        )
        // If the relay can only provide a tag (for example while its API cache
        // is warming), a valid matching version remains usable without a hash.
        assertTrue(
            siteApkMatchesLatest(
                siteVersion = "v11.74.477",
                latestVersion = "11.74.477",
                siteSha256 = null,
                latestSha256 = null,
            ),
        )
        assertFalse(siteApkMatchesLatest("latest", "v11.74.477", releaseSha, releaseSha))
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
        assertEquals("swarm_first_no_http", safeUpdateOutcome("swarm_first_no_http"))
        assertEquals("swarm_search_pending", safeUpdateOutcome("swarm_search_pending"))
        assertEquals("up_to_date", safeUpdateOutcome("up_to_date"))
        assertEquals("swarm_available", safeUpdateOutcome("swarm_available"))
        assertEquals("unknown", safeUpdateOutcome("своё-слово"))
        assertEquals("not_manual", safeManualWindow("not_manual"))
        assertEquals("not_attempted", safeManualWindow("not_attempted"))
        assertEquals("already_allowed", safeManualWindow("already_allowed"))
        assertEquals("opened", safeManualWindow("opened"))
        assertEquals("unknown", safeManualWindow("opened-by-hand"))
    }

    @Test
    fun `notification version normalization ignores v prefix and build suffix`() {
        assertEquals("11.74.474", normalizeUpdateVersion("v11.74.474"))
        assertEquals("11.74.474", normalizeUpdateVersion(" V11.74.474-beta+build.7 "))
    }

    @Test
    fun `notification is allowed only for a newer version and not repeated for same release`() {
        assertNull(updateNotificationSkipReason("11.74.473", "v11.74.474", ""))
        assertEquals("not_newer", updateNotificationSkipReason("11.74.474", "v11.74.474", ""))
        assertEquals("not_newer", updateNotificationSkipReason("11.74.475", "v11.74.474", ""))
        assertEquals("already_notified", updateNotificationSkipReason("11.74.473", "v11.74.474", "11.74.474"))
        assertEquals("already_notified", updateNotificationSkipReason("11.74.473", "11.74.474", "v11.74.474-beta"))
    }

    @Test
    fun `notification never rolls back to an older version after a newer one was shown`() {
        assertEquals("stale_version", updateNotificationSkipReason("11.74.473", "v11.74.474", "11.74.475"))
        assertNull(updateNotificationSkipReason("11.74.473", "v11.74.475", "11.74.474"))
    }

    @Test
    fun `malformed candidate cannot consume a notification attempt`() {
        assertEquals("invalid_version", updateNotificationSkipReason("11.74.473", "latest", ""))
        assertEquals("invalid_version", updateNotificationSkipReason("11.74.473", "", ""))
        assertNull(updateNotificationSkipReason("11.74.473", "v11.74.474", "broken"))
    }

    @Test
    fun `rejects malformed versions`() {
        assertFalse(isVersionNewer("development", "v11.16.2"))
        assertFalse(isVersionNewer("v11.16", "latest"))
    }
}
