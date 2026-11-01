package com.vladimir.messenger.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallHandoffTest {
    private val now = 1_700_000_000_000L
    private val sha = "ab".repeat(32)

    @Test
    fun installedHandoffIsRemovedButPendingNewerIsKept() {
        val old = "install-11.74.474-${sha.take(16)}.apk"
        val current = "install-11.74.475-${sha.take(16)}.apk"
        val newer = "install-11.74.476-${sha.take(16)}.apk"
        assertTrue(UpdateInstallHandoff.shouldDelete(old, "11.74.475", now - 60_000, now))
        assertTrue(UpdateInstallHandoff.shouldDelete(current, "11.74.475", now - 60_000, now))
        assertFalse(UpdateInstallHandoff.shouldDelete(newer, "11.74.475", now - 60_000, now))
        assertTrue(UpdateInstallHandoff.shouldDelete(newer, "11.74.475", now - ApkUpdate.TRANSFER_TTL_MS, now))
    }

    @Test
    fun legacyHandoffAndGetPatchOutputHaveGrace() {
        val legacy = "install-${sha.take(16)}.apk"
        assertFalse(UpdateInstallHandoff.shouldDelete(legacy, "11.74.475", now - 60_000, now))
        assertTrue(UpdateInstallHandoff.shouldDelete(legacy, "11.74.475", now - 24L * 60 * 60 * 1000, now))
        assertFalse(UpdateInstallHandoff.shouldDelete("APU-v11.74.475.apk", "11.74.475", now - 60_000, now))
        assertTrue(UpdateInstallHandoff.shouldDelete("APU-v11.74.475.apk", "11.74.475", now - 10 * 60 * 1000, now))
        assertFalse(UpdateInstallHandoff.shouldDelete("APU-v11.74.476.apk", "11.74.475", now - 10 * 60 * 1000, now))
        assertFalse(UpdateInstallHandoff.shouldDelete("update.bspatch", "11.74.475", now - 60_000, now))
        assertTrue(UpdateInstallHandoff.shouldDelete("update.bspatch", "11.74.475", now - 24L * 60 * 60 * 1000, now))
    }

    @Test
    fun foreignNamesAndFutureTimestampsAreNeverDeleted() {
        assertFalse(UpdateInstallHandoff.shouldDelete("user-document.apk", "11.74.475", now - 60_000, now))
        assertFalse(UpdateInstallHandoff.shouldDelete("install-11.74.475-${sha.take(16)}.apk", "11.74.475", now + 60_000, now))
        assertFalse(UpdateInstallHandoff.shouldDelete("install-11.74.475-${sha.take(16)}.apk", "11.74.475", 0, now))
    }
}
