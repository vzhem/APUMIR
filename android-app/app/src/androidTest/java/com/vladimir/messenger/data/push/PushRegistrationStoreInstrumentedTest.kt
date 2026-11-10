package com.vladimir.messenger.data.push

import android.content.ContentValues
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
    private val route = "12".repeat(32)
    private val otherRoute = "34".repeat(32)
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
        assertEquals(PushRegistrationStore.Result.Rejected, journal.registered(participants(), first.sequence, tokenHash, 1002, route))
        assertNull(ready(journal.read(participants())).announcement())
        val accepted = ready(journal.registered(participants(), second.sequence, tokenHash, 1003, route))
        assertNotNull(accepted.announcement())
        assertEquals(PushRegistrationStore.Result.Rejected, journal.registered(participants(), second.sequence, tokenHash, 1004, route))
    }

    @Test fun revocationAndRotationSurviveRestartWithoutLosingTheOldSequence() {
        val journal = store()
        val attempt = ready(journal.reserve(participants(), 1000))
        ready(journal.registered(participants(), attempt.sequence, tokenHash, 1001, route))
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

    @Test fun routeIndexIsEncryptedAndRemovedAsSoonAsRevocationBegins() {
        val journal = store()
        val attempt = ready(journal.reserve(participants(), 1000))
        assertEquals(PushRegistrationStore.RouteResult.Missing, journal.findRoute(route))
        assertEquals(PushRegistrationStore.Result.Rejected, journal.registered(participants(), attempt.sequence, tokenHash, 1001, "BAD"))
        val registered = ready(journal.registered(participants(), attempt.sequence, tokenHash, 1001, route))
        val indexed = journal.findRoute(route) as PushRegistrationStore.RouteResult.Found
        assertEquals(localId, indexed.localId)
        assertEquals(peerId, indexed.peerId)
        assertEquals(registered.generation, indexed.generation)
        assertFalse(indexed.toString().contains(localId))
        assertEquals(PushRegistrationStore.RouteResult.Unavailable, journal.findRoute("BAD"))
        val bytes = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT address FROM registrations WHERE route=?", arrayOf(route)).use { cursor ->
                assertTrue(cursor.moveToFirst()); cursor.getBlob(0)
            }
        }
        val ciphertext = bytes.toString(Charsets.ISO_8859_1)
        assertFalse(ciphertext.contains(localId))
        assertFalse(ciphertext.contains(peerId))
        assertFalse(ciphertext.contains("APUPADDR1"))
        journal.close()
        val reopened = store()
        assertEquals(peerId, (reopened.findRoute(route) as PushRegistrationStore.RouteResult.Found).peerId)
        ready(reopened.beginRevocation(participants()))
        assertEquals(PushRegistrationStore.RouteResult.Missing, reopened.findRoute(route))
        val revoke = ready(reopened.reserve(participants(), 1002))
        ready(reopened.revoked(participants(), revoke.sequence))
        assertEquals(PushRegistrationStore.RouteResult.Missing, reopened.findRoute(route))
    }

    @Test fun collidingRouteNeverEvictsAnotherRegistrationOrConfirmsTheWrongPeer() {
        val journal = store()
        val first = ready(journal.reserve(participants(), 1000))
        val secondParticipant = participants(peerBinding = other, id = otherId)
        val second = ready(journal.reserve(secondParticipant, 1000))
        ready(journal.registered(participants(), first.sequence, tokenHash, 1001, route))
        assertEquals(PushRegistrationStore.Result.Unavailable,
            journal.registered(secondParticipant, second.sequence, tokenHash, 1001, route))
        assertEquals(peerId, (journal.findRoute(route) as PushRegistrationStore.RouteResult.Found).peerId)
        val stillReserved = ready(journal.read(secondParticipant))
        assertEquals(second.sequence, stillReserved.sequence)
        assertEquals(PushRegistrationState.Reservation.REGISTER, stillReserved.reservation)
        assertNull(stillReserved.announcement())
        ready(journal.registered(secondParticipant, second.sequence, tokenHash, 1002, otherRoute))
        assertEquals(peerId, (journal.findRoute(route) as PushRegistrationStore.RouteResult.Found).peerId)
        assertEquals(otherId, (journal.findRoute(otherRoute) as PushRegistrationStore.RouteResult.Found).peerId)
    }

    @Test fun damagedRouteIndexCannotBeUsedOrSilentlyReplaced() {
        val journal = store()
        val attempt = ready(journal.reserve(participants(), 1000))
        ready(journal.registered(participants(), attempt.sequence, tokenHash, 1001, route))
        journal.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE registrations SET address = zeroblob(40)")
        }
        val reopened = store()
        assertEquals(PushRegistrationStore.RouteResult.Unavailable, reopened.findRoute(route))
        assertEquals(PushRegistrationStore.Result.Unavailable, reopened.read(participants()))
        assertEquals(PushRegistrationStore.Result.Unavailable, reopened.reserve(participants(), 1002))
    }

    @Test fun versionOneMigrationPreservesCountersButRequiresAConfirmedRoute() {
        val journal = store()
        val attempt = ready(journal.reserve(participants(), 1000))
        val accepted = ready(journal.registered(participants(), attempt.sequence, tokenHash, 1001, route))
        ready(journal.announced(participants(), accepted.generation))
        journal.close()
        val oldRow = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT record_key, payload FROM registrations", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); cursor.getString(0)!! to cursor.getBlob(1)
            }
        }
        context.deleteDatabase(dbFile.absolutePath)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE registrations (record_key TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)")
            val values = ContentValues().apply { put("record_key", oldRow.first); put("payload", oldRow.second) }
            assertTrue(db.insertOrThrow("registrations", null, values) != -1L)
            db.version = 1
        }
        val migrated = store()
        val state = ready(migrated.read(participants()))
        assertEquals(attempt.sequence, state.sequence)
        assertEquals(attempt.epochHex, state.epochHex)
        assertEquals(attempt.deviceHex, state.deviceHex)
        assertEquals(0L, state.registeredAtMs)
        assertEquals(0L, state.announcedGeneration)
        assertNull(state.tokenFingerprint)
        assertNull(state.announcement())
        assertEquals(PushRegistrationStore.RouteResult.Missing, migrated.findRoute(route))
        val retry = ready(migrated.reserve(participants(), 1002))
        assertEquals(attempt.sequence + 1, retry.sequence)
        val confirmed = ready(migrated.registered(participants(), retry.sequence, tokenHash, 1003, route))
        assertNotNull(confirmed.announcement())
        assertEquals(peerId, (migrated.findRoute(route) as PushRegistrationStore.RouteResult.Found).peerId)
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
