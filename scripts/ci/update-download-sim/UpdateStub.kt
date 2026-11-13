package com.vladimir.messenger.service
class UpdateChecker {
    data class ReleaseInfo(
        val version: String, val downloadUrl: String, val releaseNotes: String, val publishedAt: String,
        val apkSha256: String? = null, val allowSiteMirror: Boolean = true,
    )
}
internal object UpdateSiteMirror {
    const val APK_URL = "https://apu.clan.su/downloads/app-release.apk"
    data class Match(val sha256: String?)
}
