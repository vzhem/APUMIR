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
    fun `rejects malformed versions`() {
        assertFalse(isVersionNewer("development", "v11.16.2"))
        assertFalse(isVersionNewer("v11.16", "latest"))
    }
}
