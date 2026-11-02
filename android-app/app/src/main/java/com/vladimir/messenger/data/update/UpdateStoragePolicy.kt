package com.vladimir.messenger.data.update

/**
 * Pure retention decisions for redundant update copies.
 *
 * A plaintext APK may be removed only after the encrypted swarm copy is known
 * to be complete. The transfer row, wrapped key and encrypted chunks stay: they
 * are the seed. User documents in ordinary chats are never candidates.
 */
object UpdateStoragePolicy {
    private val MANAGED_APK_NAME = Regex(
        """^APU-v(\d{1,4}(?:\.\d{1,4}){0,3})(?: \(\d+\))?\.apk$""",
        RegexOption.IGNORE_CASE,
    )
    private val MANAGED_DOWNLOAD_TITLE = Regex(
        """^APU v(\d{1,4}(?:\.\d{1,4}){0,3})$""",
        RegexOption.IGNORE_CASE,
    )

    fun mayDeleteReceivedPlaintext(
        chatId: String,
        direction: String,
        state: String,
        displayName: String,
        installedVersion: String,
        encryptedSeedReady: Boolean,
        transferExpired: Boolean = false,
    ): Boolean {
        if (chatId != ApkUpdate.CHAT_ID || direction != "INCOMING" || state != "COMPLETE") return false
        if (!displayName.endsWith(".apk", ignoreCase = true)) return false
        // While the transfer is live, keep the plaintext until the encrypted
        // swarm copy can serve. After expiry the encrypted copy is gone anyway;
        // an installed APK no longer needs a second plaintext in app data.
        if (!encryptedSeedReady && !transferExpired) return false
        val version = ApkUpdate.versionFromName(displayName) ?: return false
        val comparison = ApkUpdate.compareVersions(version, installedVersion) ?: return false
        return comparison <= 0
    }

    fun mayDeleteSeedPlaintext(
        seedVersion: String,
        installedVersion: String,
        encryptedSeedReady: Boolean,
    ): Boolean {
        if (!encryptedSeedReady) return false
        val comparison = ApkUpdate.compareVersions(seedVersion, installedVersion) ?: return false
        return comparison <= 0
    }

    /**
     * Old completed update transfers are redundant once a newer APK is installed.
     * Keep one verified seed for the installed version and every newer pending
     * update; never inspect or delete ordinary-chat files.
     */
    fun mayDeleteRedundantEncryptedTransfer(
        chatId: String,
        direction: String,
        state: String,
        displayName: String,
        transferId: String,
        installedVersion: String,
        protectedSeedTransferId: String?,
        installedSeedReady: Boolean,
    ): Boolean {
        if (chatId != ApkUpdate.CHAT_ID || transferId == protectedSeedTransferId) return false
        val isFinishedUpdate =
            (direction == "INCOMING" && state == "COMPLETE") ||
                (direction == "OUTGOING" && state in setOf("COMPLETE", "SEEDING"))
        if (!isFinishedUpdate) return false
        val version = MANAGED_APK_NAME.matchEntire(displayName)?.groupValues?.get(1) ?: return false
        val comparison = ApkUpdate.compareVersions(version, installedVersion) ?: return false
        if (comparison > 0) return false
        // For the installed release, delete duplicates only when another
        // transfer is already confirmed ready to seed it.
        return comparison < 0 || installedSeedReady
    }

    /** A completed public DownloadManager copy is disposable after its version is installed. */
    fun mayDeleteInstalledDownload(title: String, installedVersion: String): Boolean {
        val version = MANAGED_DOWNLOAD_TITLE.matchEntire(title)?.groupValues?.get(1) ?: return false
        val comparison = ApkUpdate.compareVersions(version, installedVersion) ?: return false
        return comparison <= 0
    }

    /** Only app-owned hash-named seed artifacts, never metadata or user files. */
    fun isOrphanSeedArtifact(name: String, activeSeedSha: String?, activePatchSha: String?): Boolean {
        val sha = name.removeSuffix(".tmp").substringBeforeLast('.', "")
        if (!sha.matches(Regex("^[0-9a-f]{64}$"))) return false
        if (sha == activeSeedSha || sha == activePatchSha) return false
        return name == "$sha.apk" || name == "$sha.bspatch" || name == "$sha.apk.tmp"
    }
}
