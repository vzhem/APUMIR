package com.vladimir.messenger.data.push

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual Android Keystore and SQLite, not a fake secret store. */
@RunWith(AndroidJUnit4::class)
class PushEpochStoreInstrumentedTest {
    private val localId = "pk_" + "a".repeat(32)
    private val peerId = "pk_" + "b".repeat(32)
    private val otherId = "pk_" + "c".repeat(32)
    private val local = byteArrayOf(1, 2, 3)
    private val changedLocal = byteArrayOf(1, 2, 4)
    private val peer = byteArrayOf(4, 5, 6)
    private val changedPeer = byteArrayOf(4, 5, 7)
    private val other = byteArrayOf(7, 8, 9)
    private val epoch = "12".repeat(16)
    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var alias: String
    private val stores = mutableListOf<PushEpochStore>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        dbFile = File(context.noBackupFilesDir, "push-epoch-test-$id.sqlite")
        alias = "apu-push-epoch-test-$id"
    }

    @After fun tearDown() {
        stores.forEach { it.close() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        context.deleteDatabase(dbFile.absolutePath)
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
        File(dbFile.absolutePath + "-journal").delete()
    }

    private fun store(maxEntries: Int = 2048): PushEpochStore = PushEpochStore(
        context, dbFile, alias, maxEntries,
        bindingNodeId = { binding ->
            when {
                binding.contentEquals(local) || binding.contentEquals(changedLocal) -> localId
                binding.contentEquals(peer) || binding.contentEquals(changedPeer) -> peerId
                binding.contentEquals(other) -> otherId
                else -> error("invalid binding")
            }
        },
    ).also(stores::add)

    private fun offer(
        generation: Long, epochHex: String = epoch, owner: ByteArray = peer, target: ByteArray = local,
    ): PushEpochProtocol.Announcement = PushEpochProtocol.parse(PushEpochProtocol.encodeOffer(
        generation, PushEpochProtocol.bindingHash(owner), PushEpochProtocol.bindingHash(target), epochHex,
    ))!!

    private fun revoke(generation: Long): PushEpochProtocol.Announcement = PushEpochProtocol.parse(
        PushEpochProtocol.encodeRevoke(generation, PushEpochProtocol.bindingHash(peer), PushEpochProtocol.bindingHash(local)),
    )!!

    private fun PushEpochStore.acceptPeer(packet: PushEpochProtocol.Announcement) =
        accept(localId, peerId, packet, true, peer, peer, local)

    @Test fun encryptedStateSurvivesCloseAndReopenAndRejectsRollback() {
        val first = store()
        assertEquals(PushEpochStore.WriteResult.Applied, first.acceptPeer(offer(1)))
        first.close()
        val bytes = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT payload FROM epochs", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); cursor.getBlob(0)
            }
        }
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(epoch))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(PushEpochProtocol.PREFIX))
        assertTrue(dbFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        val reopened = store()
        val found = reopened.read(localId, peerId, peer, local) as PushEpochStore.ReadResult.Found
        assertEquals(1L, found.state.generation)
        assertEquals(epoch, found.state.epochHex)
        assertFalse(found.toString().contains(epoch))
        assertEquals(PushEpochStore.WriteResult.Duplicate, reopened.acceptPeer(offer(1)))
        assertEquals(PushEpochStore.WriteResult.Rejected, reopened.acceptPeer(offer(1, "34".repeat(16))))
        assertEquals(PushEpochStore.WriteResult.Applied, reopened.acceptPeer(offer(2, "56".repeat(16))))
        assertEquals(PushEpochStore.WriteResult.Rejected, reopened.acceptPeer(offer(1)))
        assertEquals(PushEpochStore.WriteResult.Applied, reopened.acceptPeer(revoke(3)))
        assertEquals(PushEpochStore.WriteResult.Rejected, reopened.acceptPeer(offer(2, "56".repeat(16))))
        val revoked = reopened.read(localId, peerId, peer, local) as PushEpochStore.ReadResult.Found
        assertEquals(PushEpochProtocol.Action.REVOKE, revoked.state.action)
        assertEquals(3L, revoked.state.generation)
    }

    @Test fun unsealedWrongPinWrongNodeAndWrongTargetNeverCreateState() {
        val store = store()
        val packet = offer(1)
        assertEquals(PushEpochStore.WriteResult.Rejected, store.accept(localId, peerId, packet, false, peer, peer, local))
        assertEquals(PushEpochStore.WriteResult.Rejected, store.accept(localId, peerId, packet, true, peer, changedPeer, local))
        assertEquals(PushEpochStore.WriteResult.Rejected, store.accept(localId, otherId, packet, true, peer, peer, local))
        assertEquals(PushEpochStore.WriteResult.Rejected, store.accept(localId, peerId, offer(1, target = changedLocal), true, peer, peer, local))
        assertEquals(PushEpochStore.WriteResult.Rejected, store.accept(localId, peerId, packet, true, peer, null, local))
        assertFalse(dbFile.exists())
        assertEquals(PushEpochStore.ReadResult.Missing, store.read(localId, peerId, peer, local))
    }

    @Test fun changedPinOrLocalBindingRequiresExplicitRecoveryAndCannotOverwriteOldState() {
        val store = store()
        assertEquals(PushEpochStore.WriteResult.Applied, store.acceptPeer(offer(5)))
        assertEquals(PushEpochStore.ReadResult.Unavailable, store.read(localId, peerId, changedPeer, local))
        assertEquals(PushEpochStore.ReadResult.Unavailable, store.read(localId, peerId, peer, changedLocal))
        assertEquals(PushEpochStore.WriteResult.Rejected, store.accept(
            localId, peerId, offer(6, owner = changedPeer), true, changedPeer, changedPeer, local,
        ))
        assertEquals(PushEpochStore.WriteResult.Rejected, store.accept(
            localId, peerId, offer(6, target = changedLocal), true, peer, peer, changedLocal,
        ))
        assertEquals(5L, (store.read(localId, peerId, peer, local) as PushEpochStore.ReadResult.Found).state.generation)
    }

    @Test fun corruptedCiphertextAndMissingKeystoreKeyFailClosed() {
        val first = store()
        assertEquals(PushEpochStore.WriteResult.Applied, first.acceptPeer(offer(1)))
        first.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE epochs SET payload = zeroblob(32)")
        }
        val afterCorruption = store()
        assertEquals(PushEpochStore.ReadResult.Unavailable, afterCorruption.read(localId, peerId, peer, local))
        assertEquals(PushEpochStore.WriteResult.Unavailable, afterCorruption.acceptPeer(offer(2)))
        afterCorruption.close()

        context.deleteDatabase(dbFile.absolutePath)
        val fresh = store()
        assertEquals(PushEpochStore.WriteResult.Applied, fresh.acceptPeer(offer(1)))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertEquals(PushEpochStore.ReadResult.Unavailable, fresh.read(localId, peerId, peer, local))
        assertEquals(PushEpochStore.WriteResult.Unavailable, fresh.acceptPeer(offer(2)))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }

    @Test fun quotaDoesNotEvictOrRearmTheFirstPeer() {
        val store = store(maxEntries = 1)
        assertEquals(PushEpochStore.WriteResult.Applied, store.acceptPeer(offer(1)))
        assertEquals(PushEpochStore.WriteResult.Full, store.accept(
            localId, otherId, offer(1, owner = other), true, other, other, local,
        ))
        assertEquals(PushEpochStore.ReadResult.Missing, store.read(localId, otherId, other, local))
        assertEquals(PushEpochStore.WriteResult.Duplicate, store.acceptPeer(offer(1)))
        assertEquals(PushEpochStore.WriteResult.Applied, store.acceptPeer(offer(2)))
    }

    @Test fun conflictingConcurrentOffersHaveExactlyOneWinnerAcrossInstances() {
        val stores = listOf(store(), store())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map { number -> executor.submit(Callable {
                assertTrue(start.await(10, TimeUnit.SECONDS))
                stores[number % 2].acceptPeer(offer(1, "%02x".format(number).repeat(16)))
            }) }
            start.countDown()
            val results = tasks.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == PushEpochStore.WriteResult.Applied })
            assertEquals(7, results.count { it == PushEpochStore.WriteResult.Rejected })
            assertEquals(1L, (stores[0].read(localId, peerId, peer, local) as PushEpochStore.ReadResult.Found).state.generation)
        } finally {
            executor.shutdownNow()
        }
    }
}
