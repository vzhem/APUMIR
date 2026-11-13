package com.vladimir.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialUpdatePolicyTest {
    private val version = "v11.74.501"

    @Test
    fun `a matching site is first and an unavailable or stale site is skipped`() {
        assertEquals(OfficialApkSource.SITE, initialOfficialApkSource(siteMatchesRelease = true))
        assertEquals(OfficialApkSource.RELAY, initialOfficialApkSource(siteMatchesRelease = false))
    }

    @Test
    fun `source failures switch site to relay to pinned GitHub without duplicates`() {
        val site = OfficialApkAttempt(10L, version, OfficialApkSource.SITE)
        assertEquals(OfficialDownloadAction.Wait, officialDownloadAction(site, 10L, OfficialDownloadObservation.ACTIVE))
        assertEquals(
            OfficialDownloadAction.TrySource(OfficialApkSource.RELAY),
            officialDownloadAction(site, 10L, OfficialDownloadObservation.SOURCE_PAUSED),
        )
        val relay = site.copy(downloadId = 11L, source = OfficialApkSource.RELAY)
        assertEquals(OfficialDownloadAction.Ignore, officialDownloadAction(relay, 10L, OfficialDownloadObservation.SOURCE_FAILED))
        assertEquals(
            OfficialDownloadAction.TrySource(OfficialApkSource.GITHUB),
            officialDownloadAction(relay, 11L, OfficialDownloadObservation.SOURCE_FAILED),
        )
        val github = relay.copy(downloadId = 12L, source = OfficialApkSource.GITHUB)
        assertEquals(OfficialDownloadAction.Wait, officialDownloadAction(github, 12L, OfficialDownloadObservation.SOURCE_PAUSED))
        assertEquals(OfficialDownloadAction.Fail, officialDownloadAction(github, 12L, OfficialDownloadObservation.SOURCE_FAILED))
        assertEquals("https://github.com/vzhem/APUMIR/releases/download/$version/app-release.apk", pinnedGithubApkUrl(version))
    }

    @Test
    fun `offline waiting local failures and cancellation do not start another source`() {
        val site = OfficialApkAttempt(10L, version, OfficialApkSource.SITE)
        assertEquals(OfficialDownloadAction.Wait, officialDownloadAction(site, 10L, OfficialDownloadObservation.WAITING_FOR_NETWORK))
        assertEquals(OfficialDownloadAction.Fail, officialDownloadAction(site, 10L, OfficialDownloadObservation.LOCAL_FAILED))
        assertEquals(OfficialDownloadAction.Fail, officialDownloadAction(site, 10L, OfficialDownloadObservation.MISSING))
        assertEquals(OfficialDownloadAction.Ignore, officialDownloadAction(null, 10L, OfficialDownloadObservation.SOURCE_FAILED))
    }

    @Test
    fun `downloaded bytes are verified before use and a bad file tries the next source`() {
        val site = OfficialApkAttempt(10L, version, OfficialApkSource.SITE)
        assertEquals(OfficialDownloadAction.Verify, officialDownloadAction(site, 10L, OfficialDownloadObservation.DOWNLOADED))
        assertEquals(OfficialDownloadAction.Ready, officialDownloadAction(site, 10L, OfficialDownloadObservation.VERIFIED))
        assertEquals(
            OfficialDownloadAction.TrySource(OfficialApkSource.RELAY),
            officialDownloadAction(site, 10L, OfficialDownloadObservation.INVALID_FILE),
        )
        val github = site.copy(downloadId = 12L, source = OfficialApkSource.GITHUB)
        assertEquals(OfficialDownloadAction.Fail, officialDownloadAction(github, 12L, OfficialDownloadObservation.INVALID_FILE))
    }

    @Test
    fun `untrusted or non-numeric tags never become GitHub URLs`() {
        assertNull(pinnedGithubApkUrl("../../other"))
        assertNull(pinnedGithubApkUrl("v1.2.3?other"))
        assertNull(pinnedGithubApkUrl("latest"))
        val relay = OfficialApkAttempt(11L, "v1.2.3-beta", OfficialApkSource.RELAY)
        assertEquals(OfficialDownloadAction.Fail, officialDownloadAction(relay, 11L, OfficialDownloadObservation.SOURCE_FAILED))
    }
}
