package com.vladimir.messenger.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStoragePolicyTest {
    @Test
    fun receivedPlaintextIsRemovedOnlyForInstalledUpdateInVirtualSeedChat() {
        fun may(chat: String = ApkUpdate.CHAT_ID, name: String = "APU-v11.74.475.apk", ready: Boolean = true, expired: Boolean = false) =
            UpdateStoragePolicy.mayDeleteReceivedPlaintext(
                chatId = chat,
                direction = "INCOMING",
                state = "COMPLETE",
                displayName = name,
                installedVersion = "11.74.475",
                encryptedSeedReady = ready,
                transferExpired = expired,
            )
        assertTrue(may())
        assertTrue(may(name = "APU-v11.74.474.apk"))
        assertFalse(may(name = "APU-v11.74.476.apk"))
        assertFalse(may(chat = "personal-chat"))
        assertFalse(may(name = "document.apk"))
        assertFalse(may(ready = false))
        assertTrue(may(ready = false, expired = true))
        assertFalse(UpdateStoragePolicy.mayDeleteReceivedPlaintext(
            ApkUpdate.CHAT_ID, "INCOMING", "TRANSFERRING", "APU-v11.74.475.apk", "11.74.475", true,
        ))
    }

    @Test
    fun encryptedCleanupKeepsCurrentSeedAndPendingNewerUpdates() {
        fun may(
            chat: String = ApkUpdate.CHAT_ID,
            direction: String = "INCOMING",
            state: String = "COMPLETE",
            name: String = "APU-v11.74.474.apk",
            transferId: String = "old-transfer",
            protectedSeedId: String? = "current-seed",
            installedSeedReady: Boolean = true,
        ) = UpdateStoragePolicy.mayDeleteRedundantEncryptedTransfer(
            chatId = chat,
            direction = direction,
            state = state,
            displayName = name,
            transferId = transferId,
            installedVersion = "11.74.475",
            protectedSeedTransferId = protectedSeedId,
            installedSeedReady = installedSeedReady,
        )

        assertTrue(may()) // Older installed update can be discarded.
        assertTrue(may(name = "APU-v11.74.475.apk")) // Duplicate only after current seed is ready.
        assertFalse(may(name = "APU-v11.74.476.apk")) // Newer update is pending.
        assertFalse(may(transferId = "current-seed"))
        assertFalse(may(name = "APU-v11.74.475.apk", installedSeedReady = false))
        assertFalse(may(chat = "personal-chat"))
        assertFalse(may(name = "notes.apk"))
        assertFalse(may(name = "MyApp-11.74.474.apk"))
        assertFalse(may(state = "TRANSFERRING"))
        assertFalse(may(direction = "OUTGOING", state = "TRANSFERRING"))
    }

    @Test
    fun installedPublicDownloadCanBeRemovedButPendingAndUnrelatedDownloadsAreKept() {
        assertTrue(UpdateStoragePolicy.mayDeleteInstalledDownload("APU v11.74.475", "11.74.475"))
        assertTrue(UpdateStoragePolicy.mayDeleteInstalledDownload("APU v11.74.474", "11.74.475"))
        assertFalse(UpdateStoragePolicy.mayDeleteInstalledDownload("APU v11.74.476", "11.74.475"))
        assertFalse(UpdateStoragePolicy.mayDeleteInstalledDownload("Other App 1.2.3", "11.74.475"))
        assertFalse(UpdateStoragePolicy.mayDeleteInstalledDownload("APU update", "11.74.475"))
        assertFalse(UpdateStoragePolicy.mayDeleteInstalledDownload("APU v11.74.474 beta", "11.74.475"))
    }

    @Test
    fun seedPlaintextNeedsAReadyEncryptedCopyAndInstalledVersion() {
        assertTrue(UpdateStoragePolicy.mayDeleteSeedPlaintext("11.74.475", "11.74.475", true))
        assertTrue(UpdateStoragePolicy.mayDeleteSeedPlaintext("11.74.474", "11.74.475", true))
        assertFalse(UpdateStoragePolicy.mayDeleteSeedPlaintext("11.74.476", "11.74.475", true))
        assertFalse(UpdateStoragePolicy.mayDeleteSeedPlaintext("11.74.475", "11.74.475", false))
    }

    @Test
    fun orphanRuleProtectsActiveArtifactsAndMetadata() {
        val sha = "ab".repeat(32)
        val other = "cd".repeat(32)
        assertTrue(UpdateStoragePolicy.isOrphanSeedArtifact("$other.apk", sha, null))
        assertTrue(UpdateStoragePolicy.isOrphanSeedArtifact("$other.apk.tmp", sha, null))
        assertFalse(UpdateStoragePolicy.isOrphanSeedArtifact("$sha.apk", sha, null))
        assertFalse(UpdateStoragePolicy.isOrphanSeedArtifact("$other.bspatch", null, other))
        assertFalse(UpdateStoragePolicy.isOrphanSeedArtifact("seed.v1", sha, null))
        assertFalse(UpdateStoragePolicy.isOrphanSeedArtifact("../$other.apk", sha, null))
    }
}
