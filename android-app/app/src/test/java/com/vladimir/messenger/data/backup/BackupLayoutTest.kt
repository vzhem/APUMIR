package com.vladimir.messenger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupLayoutTest {

    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun knownEntriesAreClassified() {
        assertEquals(BackupLayout.Entry.Manifest, BackupLayout.classify("manifest.txt"))
        assertEquals(BackupLayout.Entry.Database, BackupLayout.classify("db/messenger_database"))
        assertEquals(BackupLayout.Entry.DatabaseWal, BackupLayout.classify("db/messenger_database-wal"))
        assertEquals(BackupLayout.Entry.Prefs("p2p_prefs"), BackupLayout.classify("prefs/p2p_prefs.txt"))
        assertEquals(BackupLayout.Entry.FileExchangeSecret, BackupLayout.classify("secrets/file_exchange_x25519"))
        assertEquals(BackupLayout.Entry.SigningSeed, BackupLayout.classify("secrets/identity_signing_seed"))
        assertEquals(BackupLayout.Entry.Avatar("123_avatar_cropped.jpg"), BackupLayout.classify("avatar/123_avatar_cropped.jpg"))
        assertEquals(BackupLayout.Entry.Preview(id), BackupLayout.classify("preview/$id.jpg"))
        assertEquals(BackupLayout.Entry.Received(id, "photo.jpg"), BackupLayout.classify("received/$id/photo.jpg"))
    }

    @Test
    fun escapesAndStrangersAreRejected() {
        assertNull(BackupLayout.classify(""))
        assertNull(BackupLayout.classify("db/../../shared_prefs/p2p_prefs.xml"))
        assertNull(BackupLayout.classify("avatar/../evil"))
        assertNull(BackupLayout.classify("avatar/.hidden"))
        assertNull(BackupLayout.classify("avatar/sub/dir.jpg"))
        assertNull(BackupLayout.classify("prefs/../x.txt"))
        assertNull(BackupLayout.classify("prefs/P2P.txt"))
        assertNull(BackupLayout.classify("preview/notanid.jpg"))
        assertNull(BackupLayout.classify("preview/$id.png"))
        assertNull(BackupLayout.classify("received/$id"))
        assertNull(BackupLayout.classify("received/$id/"))
        assertNull(BackupLayout.classify("received/$id/a/b"))
        assertNull(BackupLayout.classify("received/short/a"))
        assertNull(BackupLayout.classify("secrets/other"))
        assertNull(BackupLayout.classify("/etc/passwd"))
    }

    @Test
    fun prefsEntryNameRoundTrips() {
        for (name in BackupLayout.PREFS_NAMES) {
            assertEquals(BackupLayout.Entry.Prefs(name), BackupLayout.classify(BackupLayout.prefsEntry(name)))
        }
    }
}
