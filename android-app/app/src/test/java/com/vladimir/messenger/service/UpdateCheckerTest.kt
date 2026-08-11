package com.vladimir.messenger.service

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
    fun `rejects malformed versions`() {
        assertFalse(isVersionNewer("development", "v11.16.2"))
        assertFalse(isVersionNewer("v11.16", "latest"))
    }
}
