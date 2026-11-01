package com.vladimir.messenger.data.update

/**
 * Pure retention decisions for redundant update copies.
 *
 * A plaintext APK may be removed only after the encrypted swarm copy is known
 * to be complete. The transfer row, wrapped key and encrypted chunks stay: they
 * are the seed. User documents in ordinary chats are never candidates.
 */
object UpdateStoragePolicy {
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

    /** Only app-owned hash-named seed artifacts, never metadata or user files. */
    fun isOrphanSeedArtifact(name: String, activeSeedSha: String?, activePatchSha: String?): Boolean {
        val sha = name.substringBeforeLast('.', "")
        if (!sha.matches(Regex("^[0-9a-f]{64}$"))) return false
        if (sha == activeSeedSha || sha == activePatchSha) return false
        return name == "$sha.apk" || name == "$sha.bspatch" || name == "$sha.apk.tmp"
    }
}
