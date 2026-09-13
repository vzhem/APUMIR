package com.vladimir.messenger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {

    private val manifest = BackupManifest(
        dbVersion = 20,
        appVersionName = "v11.70.16",
        appVersionCode = 11_070_016,
        createdAtMs = 1_757_700_000_000L,
        nodeId = "pk_52b8c6e5abce214b9347a6acc90588f2",
        displayName = "Анна Каренина",
        nickname = "anna_k",
        includesReceived = true,
        receivedFiles = 3,
        receivedBytes = 1234567L,
    )

    @Test
    fun roundTrip() {
        assertEquals(manifest, BackupManifest.decode(manifest.encode()))
    }

    @Test
    fun unknownKeysAreIgnoredSoOldAppsCanStillReadTheHeader() {
        val text = manifest.encode() + "future_field=whatever\n"
        assertEquals(manifest, BackupManifest.decode(text))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(BackupManifest.decode(""))
        assertNull(BackupManifest.decode("apu-prefs-v1\n"))
        assertNull(BackupManifest.decode("apu-backup-manifest\nformat=1\n"))          // нет db_version
        assertNull(BackupManifest.decode("apu-backup-manifest\nformat=x\ndb_version=1\n"))
        assertNull(BackupManifest.decode("apu-backup-manifest\nbroken line\n"))
    }

    @Test
    fun namesAreSanitisedOnEncode() {
        val dirty = manifest.copy(nodeId = "pk_ab\ncd=1", nickname = "ni ck", appVersionName = "v1\n=2")
        val decoded = BackupManifest.decode(dirty.encode())!!
        assertEquals("pk_abcd1", decoded.nodeId)
        assertEquals("nick", decoded.nickname)
        assertEquals("v12", decoded.appVersionName)
        assertTrue(decoded.displayName == manifest.displayName)
    }
}
