package com.vladimir.messenger.service

/** The user's update download is one logical attempt, even when its source changes. */
internal enum class OfficialApkSource { SITE, RELAY, GITHUB }

internal data class OfficialApkAttempt(
    val downloadId: Long,
    val version: String,
    val source: OfficialApkSource,
)

internal enum class OfficialDownloadObservation {
    ACTIVE,
    WAITING_FOR_NETWORK,
    SOURCE_PAUSED,
    SOURCE_FAILED,
    LOCAL_FAILED,
    DOWNLOADED,
    VERIFIED,
    INVALID_FILE,
    MISSING,
}

internal sealed interface OfficialDownloadAction {
    data object Ignore : OfficialDownloadAction
    data object Wait : OfficialDownloadAction
    data object Verify : OfficialDownloadAction
    data object Ready : OfficialDownloadAction
    data object Fail : OfficialDownloadAction
    data class TrySource(val source: OfficialApkSource) : OfficialDownloadAction
}

internal fun initialOfficialApkSource(siteMatchesRelease: Boolean): OfficialApkSource =
    if (siteMatchesRelease) OfficialApkSource.SITE else OfficialApkSource.RELAY

/** A direct GitHub fallback is pinned to the displayed release, never to /latest. */
internal fun pinnedGithubApkUrl(version: String): String? {
    val tag = version.trim()
    if (!Regex("[vV]?[0-9]{1,4}(\\.[0-9]{1,4}){0,3}").matches(tag)) return null
    return "https://github.com/vzhem/APUMIR/releases/download/$tag/app-release.apk"
}

private fun nextOfficialApkSource(attempt: OfficialApkAttempt): OfficialApkSource? = when (attempt.source) {
    OfficialApkSource.SITE -> OfficialApkSource.RELAY
    OfficialApkSource.RELAY -> if (pinnedGithubApkUrl(attempt.version) != null) OfficialApkSource.GITHUB else null
    OfficialApkSource.GITHUB -> null
}

/**
 * Decisions are idempotent: a late broadcast for an older attempt cannot start
 * a second download. A missing/cancelled job or a local storage failure must
 * never be interpreted as an unavailable website.
 */
internal fun officialDownloadAction(
    current: OfficialApkAttempt?,
    eventDownloadId: Long,
    observation: OfficialDownloadObservation,
): OfficialDownloadAction {
    if (current == null || current.downloadId != eventDownloadId) return OfficialDownloadAction.Ignore
    return when (observation) {
        OfficialDownloadObservation.ACTIVE,
        OfficialDownloadObservation.WAITING_FOR_NETWORK -> OfficialDownloadAction.Wait
        OfficialDownloadObservation.SOURCE_PAUSED ->
            nextOfficialApkSource(current)?.let(OfficialDownloadAction::TrySource) ?: OfficialDownloadAction.Wait
        OfficialDownloadObservation.SOURCE_FAILED,
        OfficialDownloadObservation.INVALID_FILE ->
            nextOfficialApkSource(current)?.let(OfficialDownloadAction::TrySource) ?: OfficialDownloadAction.Fail
        OfficialDownloadObservation.LOCAL_FAILED,
        OfficialDownloadObservation.MISSING -> OfficialDownloadAction.Fail
        OfficialDownloadObservation.DOWNLOADED -> OfficialDownloadAction.Verify
        OfficialDownloadObservation.VERIFIED -> OfficialDownloadAction.Ready
    }
}
