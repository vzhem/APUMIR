package com.vladimir.messenger.data.push

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android Keystore, real SQLite, real AES-GCM on the token journal. The
 * provider token is a fixed test string; this does NOT contact Firebase, register
 * a device, or prove delivery.
 */
@RunWith(AndroidJUnit4::class)
class FcmTokenJournalInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val file = File(context.noBackupFilesDir, "push-fcm-token-$suffix.sqlite")
    private val alias = "apu-push-fcm-token-test-$suffix"
    private val token = "fcm-instrumented-token-0123456789"
    private val rotated = "fcm-instrumented-token-9876543210"

    private fun journal() = FcmTokenJournal(context, file, alias)

    @After fun cleanUp() {
        journal().close()
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    @Test fun firstTokenIsMissingThenRotationIsDetectedAndSurvivesReopen() {
        val first = journal()
        assertEquals(FcmTokenJournal.State.Missing, first.record(token, 1_700_000_000_000L))
        assertEquals(FcmTokenJournal.State.Missing, first.record(token, 1_700_000_001_000L))
        assertTrue(first.confirmRegistered(token))
        assertEquals(FcmTokenJournal.State.Unchanged, first.record(token, 1_700_000_001_100L))
        assertEquals(FcmTokenJournal.State.Rotated, first.record(rotated, 1_700_000_002_000L))
        assertTrue(first.confirmRegistered(rotated))
        assertEquals(FcmTokenJournal.State.Unchanged, first.record(rotated, 1_700_000_003_000L))
        first.close()

        val reopened = journal()
        assertEquals(FcmTokenJournal.State.Unchanged, reopened.record(rotated, 1_700_000_004_000L))
        assertEquals(FcmTokenJournal.State.Rotated, reopened.record(token, 1_700_000_005_000L))
    }

    @Test fun thePlaintextTokenNeverAppearsInTheDatabaseFile() {
        val first = journal()
        assertEquals(FcmTokenJournal.State.Missing, first.record(token, 1_700_000_000_000L))
        first.close()
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains(token))
        assertTrue(bytes.isNotEmpty())
    }

    @Test fun malformedTokenIsRejectedWithoutBeingStored() {
        val first = journal()
        assertEquals(FcmTokenJournal.State.Unavailable, first.record("short", 1))
        assertEquals(FcmTokenJournal.State.Unavailable, first.record("has space or\nnewline inside", 1))
        assertEquals(FcmTokenJournal.State.Missing, first.record(token, 1))
    }

    @Test fun forgetMakesTheNextTokenLookNew() {
        val first = journal()
        assertEquals(FcmTokenJournal.State.Missing, first.record(token, 1))
        assertTrue(first.forget())
        assertEquals(FcmTokenJournal.State.Missing, first.record(token, 2))
    }

    @Test fun legacyRowAuthenticatesAndForcesRegistrationBeforeUsingNewFormat() {
        val first = journal()
        assertEquals(FcmTokenJournal.State.Missing, first.record(token, 1))
        first.close()
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
        val fingerprint = requireNotNull(PushWakeCrypto.tokenFingerprint(token))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("apu-push-fcm-token-v1\u0000$fingerprint".toByteArray(Charsets.US_ASCII))
        val blob = byteArrayOf(1) + cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE fcm_token SET payload=? WHERE id=1", arrayOf(blob))
        }
        val reopened = journal()
        // A different token cannot authenticate the legacy row and must not replace it.
        assertEquals(FcmTokenJournal.State.Unavailable, reopened.record(rotated, 2))
        assertFalse(reopened.present())
        assertEquals(FcmTokenJournal.State.Missing, reopened.record(token, 3))
        assertTrue(reopened.present())
        assertEquals(FcmTokenJournal.State.Missing, reopened.record(token, 4))
        assertTrue(reopened.confirmRegistered(token))
        assertEquals(FcmTokenJournal.State.Unchanged, reopened.record(token, 4))
        assertEquals(FcmTokenJournal.State.Rotated, reopened.record(rotated, 5))
        reopened.close()
    }

    @Test fun pendingRegistrationSurvivesReopenAndCannotConfirmAnotherToken() {
        val first = journal()
        assertEquals(FcmTokenJournal.State.Missing, first.record(token, 1))
        assertFalse(first.confirmRegistered(rotated))
        first.close()
        val reopened = journal()
        assertEquals(FcmTokenJournal.State.Missing, reopened.record(token, 2))
        assertTrue(reopened.confirmRegistered(token))
        reopened.close()
        val after = journal()
        assertEquals(FcmTokenJournal.State.Unchanged, after.record(token, 3))
        after.close()
    }

    @Test fun aLostKeystoreKeyIsNotSilentlyReplaced() {
        val first = journal()
        assertEquals(FcmTokenJournal.State.Missing, first.record(token, 1))
        first.close()
        // Losing the device key must fail closed, not overwrite the journal.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        val reopened = journal()
        assertEquals(FcmTokenJournal.State.Unavailable, reopened.record(rotated, 2))
    }
}
