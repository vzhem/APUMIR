package com.vladimir.messenger.data.push

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite and Android Keystore; binding verification is deliberately stubbed. */
@RunWith(AndroidJUnit4::class)
class PushRegistrationStoreInstrumentedTest {
    private val localId = "pk_" + "a".repeat(32)
    private val peerId = "pk_" + "b".repeat(32)
    private val otherId = "pk_" + "c".repeat(32)
    private val local = byteArrayOf(1, 2, 3)
    private val changedLocal = byteArrayOf(1, 2, 4)
    private val peer = byteArrayOf(4, 5, 6)
    private val changedPeer = byteArrayOf(4, 5, 7)
    private val other = byteArrayOf(7, 8, 9)
    private val tokenHash = "ab".repeat(32)
    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var alias: String
    private val stores = mutableListOf<PushRegistrationStore>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        dbFile = File(context.noBackupFilesDir, "push-registration-test-$id.sqlite")
        alias = "apu-push-registration-test-$id"
    }

    @After fun tearDown() {
        stores.forEach { it.close() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        context.deleteDatabase(dbFile.absolutePath)
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
        File(dbFile.absolutePath + "-journal").delete()
    }

    private fun participants(localBinding: ByteArray = local, peerBinding: ByteArray = peer, id: String = peerId) =
        PushRegistrationStore.Participants(localId, id, localBinding, peerBinding)

    private fun store(maxEntries: Int = 2048, random: SecureRandom = SecureRandom()) = PushRegistrationStore(
        context, dbFile, alias, maxEntries,
        bindingNodeId = { binding ->
            when {
                binding.contentEquals(local) || binding.contentEquals(changedLocal) -> localId
                binding.contentEquals(peer) || binding.contentEquals(changedPeer) -> peerId
                binding.contentEquals(other) -> otherId
                else -> error("invalid binding")
            }
        },
        random = random,
    ).also(stores::add)

    private fun ready(result: PushRegistrationStore.Result) = (result as PushRegistrationStore.Result.Ready).state

    @Test fun reservationIsEncryptedAndSurvivesReopen() {
        val first = store()
        val attempt = ready(first.reserve(participants(), 1_700_000_000_000))
        assertEquals(1L, attempt.sequence)
        assertNull(attempt.announcement())
        first.close()
        val bytes = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT payload FROM registrations", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); cursor.getBlob(0)
            }
        }
        val payload = bytes.toString(Charsets.ISO_8859_1)
        assertFalse(payload.contains(attempt.epochHex))
        assertFalse(payload.contains(attempt.deviceHex))
        assertFalse(payload.contains("APUPREG1"))
        assertTrue(dbFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        val reopened = store()
        val stored = ready(reopened.read(participants()))
        assertEquals(attempt.epochHex, stored.epochHex)
        assertEquals(attempt.deviceHex, stored.deviceHex)
        assertEquals(1L, stored.sequence)
        assertEquals(2L, ready(reopened.reserve(participants(), 1_700_000_000_001)).sequence)
        assertFalse(stored.toString().contains(stored.epochHex))
        assertFalse(participants().toString().contains(localId))
    }

    @Test fun concurrentReservationsNeverReuseASequenceAcrossInstances() {
        val journals = listOf(store(), store())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map { number -> executor.submit(Callable {
                assertTrue(start.await(10, TimeUnit.SECONDS))
                ready(journals[number % 2].reserve(participants(), 1_700_000_000_000))
            }) }
            start.countDown()
            val states = tasks.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals((1L..8L).toList(), states.map { it.sequence }.sorted())
            assertEquals(1, states.map { it.epochHex }.distinct().size)
            assertEquals(1, states.map { it.deviceHex }.distinct().size)
            assertEquals(8L, ready(journals[0].read(participants())).sequence)
        } finally { executor.shutdownNow() }
    }

    @Test fun bindingChangesAndWrongNodesCannotOverwriteExistingState() {
        val journal = store()
        val initial = ready(journal.reserve(participants(), 1000))
        assertEquals(PushRegistrationStore.Result.Rejected, journal.reserve(participants(peerBinding = changedPeer), 1001))
        assertEquals(PushRegistrationStore.Result.Rejected, journal.reserve(participants(localBinding = changedLocal), 1001))
        assertEquals(PushRegistrationStore.Result.Rejected, journal.reserve(participants(peerBinding = peer, id = otherId), 1001))
        assertEquals(PushRegistrationStore.Result.Rejected, journal.reserve(participants(peerBinding = ByteArray(513)), 1001))
        assertEquals(initial.epochHex, ready(journal.read(participants())).epochHex)
        assertEquals(1L, ready(journal.read(participants())).sequence)
    }

    @Test fun delayedRegistrationResponseCannotConfirmANewerAttempt() {
        val journal = store()
        val first = ready(journal.reserve(participants(), 1000))
        val second = ready(journal.reserve(participants(), 1001))
        assertEquals(PushRegistrationStore.Result.Rejected, journal.registered(participants(), first.sequence, tokenHash, 1002))
        assertNull(ready(journal.read(participants())).announcement())
        val accepted = ready(journal.registered(participants(), second.sequence, tokenHash, 1003))
        assertNotNull(accepted.announcement())
        assertEquals(PushRegistrationStore.Result.Rejected, journal.registered(participants(), second.sequence, tokenHash, 1004))
    }

    @Test fun revocationAndRotationSurviveRestartWithoutLosingTheOldSequence() {
        val journal = store()
        val attempt = ready(journal.reserve(participants(), 1000))
        ready(journal.registered(participants(), attempt.sequence, tokenHash, 1001))
        val revoking = ready(journal.beginRevocation(participants()))
        assertEquals(1001L, revoking.generation)
        assertEquals(PushRegistrationStore.Result.Rejected, journal.revoked(participants(), attempt.sequence))
        val revoke = ready(journal.reserve(participants(), 1002))
        journal.close()
        val reopened = store()
        assertEquals(PushRegistrationState.Phase.REVOKING, ready(reopened.read(participants())).phase)
        val done = ready(reopened.revoked(participants(), revoke.sequence))
        assertEquals(PushRegistrationStore.Result.Rejected, reopened.rotate(participants()))
        ready(reopened.announced(participants(), done.generation))
        val rotated = ready(reopened.rotate(participants()))
        assertEquals(1002L, rotated.generation)
        assertEquals(0L, rotated.sequence)
        assertFalse(rotated.epochHex == attempt.epochHex)
        assertEquals(attempt.deviceHex, rotated.deviceHex)
        assertNull(rotated.announcement())
    }

    @Test fun corruptionOrMissingKeystoreKeyNeverSilentlyResetsState() {
        val journal = store()
        ready(journal.reserve(participants(), 1000))
        journal.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE registrations SET payload = zeroblob(32)")
        }
        val corrupt = store()
        assertEquals(PushRegistrationStore.Result.Unavailable, corrupt.read(participants()))
        assertEquals(PushRegistrationStore.Result.Unavailable, corrupt.reserve(participants(), 1001))
        corrupt.close()
        context.deleteDatabase(dbFile.absolutePath)
        val fresh = store()
        ready(fresh.reserve(participants(), 1002))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertEquals(PushRegistrationStore.Result.Unavailable, fresh.read(participants()))
        assertEquals(PushRegistrationStore.Result.Unavailable, fresh.reserve(participants(), 1003))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }

    @Test fun quotaAndFailedEntropyDoNotEvictOrCreateARegistration() {
        val journal = store(maxEntries = 1)
        val first = ready(journal.reserve(participants(), 1000))
        assertEquals(PushRegistrationStore.Result.Full, journal.reserve(participants(peerBinding = other, id = otherId), 1000))
        assertEquals(first.epochHex, ready(journal.read(participants())).epochHex)
        journal.close()
        context.deleteDatabase(dbFile.absolutePath)
        val zeroRandom = object : SecureRandom() { override fun nextBytes(bytes: ByteArray) { bytes.fill(0) } }
        val noEntropy = store(random = zeroRandom)
        assertEquals(PushRegistrationStore.Result.Unavailable, noEntropy.reserve(participants(), 1000))
        assertEquals(PushRegistrationStore.Result.Missing, noEntropy.read(participants()))
    }
}
