package com.vladimir.messenger.data.push

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Android SQLite, Keystore and AES-GCM; binding verification and the
 * key-envelope FFI are deliberately faked. There is no provider or router here.
 */
@RunWith(AndroidJUnit4::class)
class PushMailboxOutboxInstrumentedTest {
    private val sender = "pk_" + "1".repeat(32)
    private val recipient = "pk_" + "2".repeat(32)
    private val local = byteArrayOf(1, 3, 5)
    private val peer = byteArrayOf(2, 4, 6)
    private val secret = ByteArray(32) { 4 }
    private val originalId = "01234567-89ab-4cde-8f01-23456789abcd"
    private val body = "Сообщение для спящего собеседника 😀"
    private var now = 1_700_000_000_000L
    private val failures = AtomicInteger()
    private val seals = AtomicInteger()
    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var alias: String
    private val stores = mutableListOf<PushMailboxOutbox>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        dbFile = File(context.noBackupFilesDir, "push-outbox-test-$suffix.sqlite")
        alias = "apu-push-outbox-test-$suffix"
        failures.set(0)
        seals.set(0)
    }

    @After fun tearDown() {
        stores.forEach { it.close() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        context.deleteDatabase(dbFile.absolutePath)
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.absolutePath + it).delete() }
    }

    private fun nodes(binding: ByteArray): String = when (binding.firstOrNull()) {
        1.toByte() -> sender
        2.toByte() -> recipient
        else -> error("bad fake binding")
    }

    private fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    // This is only a structurally valid test envelope, not a real Rust signature.
    private fun fakeEnvelope(sender: ByteArray, recipient: ByteArray, manifest: ByteArray): ByteArray =
        ByteArray(sender.size + 203) { 6 }.also { out ->
            out[0] = 1
            out[1] = (sender.size ushr 8).toByte()
            out[2] = sender.size.toByte()
            sender.copyInto(out, 3)
            hash(recipient).copyInto(out, 3 + sender.size)
            hash(manifest).copyInto(out, 3 + sender.size + 32)
        }

    private fun sealer(beforeSeal: () -> Unit = {}): PushMailboxMessageSealer = PushMailboxMessageSealer(
        random = SecureRandom(),
        bindingNodeId = ::nodes,
        createKey = { a, b, _, manifest, _ ->
            seals.incrementAndGet()
            beforeSeal()
            fakeEnvelope(a, b, manifest)
        },
        recordFailure = { failures.incrementAndGet() },
    )

    private fun store(
        maxRecords: Int = 50_000,
        maxActiveBytes: Long = 16L * 1024 * 1024,
        beforeSeal: () -> Unit = {},
    ) = PushMailboxOutbox(
        context = context, sealer = sealer(beforeSeal), recordFailure = { failures.incrementAndGet() },
        databaseFile = dbFile, keyAlias = alias, maxRecords = maxRecords, maxActiveBytes = maxActiveBytes,
        clock = { now }, bindingNodeId = ::nodes,
    ).also(stores::add)

    private fun epoch(
        local: ByteArray = this.local,
        peer: ByteArray = this.peer,
        generation: Long = 1,
        epochHex: String = "ab".repeat(16),
    ): PushEpochProtocol.State {
        val wire = PushEpochProtocol.encodeOffer(generation, PushEpochProtocol.bindingHash(peer),
            PushEpochProtocol.bindingHash(local), epochHex)!!
        val packet = PushEpochProtocol.parse(wire)!!
        return (PushEpochProtocol.accept(packet, true, peer, peer, local, null) as PushEpochProtocol.Verdict.Applied).state
    }

    private fun draft(
        id: String = originalId,
        content: String = body,
        local: ByteArray = this.local,
        peer: ByteArray = this.peer,
        secret: ByteArray = this.secret,
        state: PushEpochProtocol.State = epoch(local, peer),
        cutoff: Long = now + 86_400_000L,
        sentAt: Long = 1_700_000_000_000L,
    ) = PushMailboxOutbox.Draft(id, sender, recipient, sentAt, content, local, peer, secret, state, cutoff)

    private fun prepare(store: PushMailboxOutbox, draft: PushMailboxOutbox.Draft = draft()) =
        store.prepare(draft) { true }

    private fun result(store: PushMailboxOutbox, draft: PushMailboxOutbox.Draft = draft()) =
        prepare(store, draft) as PushMailboxOutbox.Result.Ready

    private fun rawRows(): Int = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM mailbox_outbox", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    @Test fun unsupportedBodiesNeverCreateOrReadAnOutboxRecord() {
        val outbox = store()
        for (body in listOf("APUQUIET1:quiet", "ack|id", "words\nAPUIMG1:photo", "a:b")) {
            assertEquals(PushMailboxOutbox.Result.Rejected, prepare(outbox, draft(content = body)))
        }
        assertEquals(0, seals.get())
        assertEquals(0, failures.get())
        assertFalse(dbFile.exists())
    }

    @Test fun committedTripleSurvivesReopenAndALostResponseWithoutResealing() {
        val first = store()
        val initial = result(first)
        assertTrue(initial.created)
        assertEquals(PushMailboxProtocol.putId(initial.entry.expiresAt, initial.entry.envelope), initial.entry.id)
        assertEquals(1, seals.get())
        assertFalse(initial.toString().contains(body))
        assertFalse(initial.entry.toString().contains(initial.entry.id))
        assertFalse(draft().toString().contains(body))
        assertTrue(dbFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        first.close()
        val onDisk = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT record_key, payload FROM mailbox_outbox", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0) + cursor.getBlob(1).toString(Charsets.ISO_8859_1)
            }
        }
        listOf(body, originalId, initial.entry.id, initial.entry.envelope).forEach { assertFalse(onDisk.contains(it)) }

        // The real builder and HTTP codec are used, but the FFI and HTTP server
        // are fake. A lost response must not change the next request body.
        val material = PushWakeCrypto(
            derive = { _, _, _, _, _ -> ByteArray(64) { if (it < 32) 0x12 else 0x34 } },
            recordFailure = { failures.incrementAndGet() },
        ).derive(local, peer, secret, "ab".repeat(16), true)!!
        try {
            fun request(entry: PushMailboxOutbox.Entry) = material.mailboxPut(
                local, peer, epoch(), entry.id, entry.expiresAt, entry.envelope,
            ) { true }!!
            val sent = mutableListOf<String>()
            val http = PushMailboxHttpClient(
                PushEndpoint.approved("https://wake.example.workers.dev", setOf("wake.example.workers.dev"))!!,
                PushMailboxPost { _, path, bytes ->
                    assertEquals("/v1/mailbox/put", path)
                    sent.add(bytes.toString(Charsets.US_ASCII))
                    if (sent.size == 1) throw IOException("fake lost response")
                    PushMailboxHttpResponse(200, "{\"state\":\"stored\",\"expiresAt\":${initial.entry.expiresAt},\"wakeQueued\":true}".toByteArray())
                },
                { failures.incrementAndGet() },
            )
            assertEquals(PushMailboxHttpClient.PutResult.Unavailable, runBlocking { http.put(request(initial.entry)) })
            val reopened = store()
            val repeated = result(reopened, draft(cutoff = now + 2 * 86_400_000L))
            assertFalse(repeated.created)
            assertEquals(initial.entry.id, repeated.entry.id)
            assertEquals(initial.entry.expiresAt, repeated.entry.expiresAt)
            assertEquals(initial.entry.envelope, repeated.entry.envelope)
            assertEquals(1, seals.get())
            assertTrue(runBlocking { http.put(request(repeated.entry)) } is PushMailboxHttpClient.PutResult.Stored)
            assertEquals(sent[0], sent[1])
            // Server acceptance cannot retire the local record.
            assertFalse(result(reopened).created)
            assertEquals(1, failures.get()) // fake lost HTTP response
        } finally { material.close() }
    }

    @Test fun concurrentDifferentCiphertextsAndCutoffsCommitOneWinner() {
        val participants = 8
        val allSealing = CountDownLatch(participants)
        val pool = Executors.newFixedThreadPool(participants)
        val outboxes = (0 until participants).map {
            store(beforeSeal = {
                allSealing.countDown()
                check(allSealing.await(20, TimeUnit.SECONDS))
            })
        }
        val start = CountDownLatch(1)
        try {
            val tasks = outboxes.mapIndexed { index, outbox -> pool.submit(Callable {
                check(start.await(10, TimeUnit.SECONDS))
                result(outbox, draft(cutoff = now + 86_400_000L + index))
            }) }
            start.countDown()
            val results = tasks.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(participants, seals.get())
            assertEquals(1, results.count { it.created })
            assertEquals(1, results.map { it.entry.id }.distinct().size)
            assertEquals(1, results.map { it.entry.envelope }.distinct().size)
            assertEquals(1, results.map { it.entry.expiresAt }.distinct().size)
            assertEquals(1, rawRows())
            assertEquals(0, failures.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun changedMessagePinBindingOrEpochCannotReplaceAnExistingRecord() {
        val outbox = store()
        val saved = result(outbox)
        assertEquals(PushMailboxOutbox.Result.Conflict, prepare(outbox, draft(content = body + " changed")))
        assertEquals(PushMailboxOutbox.Result.Conflict, prepare(outbox, draft(sentAt = 1_700_000_000_001L)))
        val changedLocal = byteArrayOf(1, 3, 9)
        val changedPeer = byteArrayOf(2, 4, 9)
        assertEquals(PushMailboxOutbox.Result.Conflict, prepare(outbox, draft(local = changedLocal)))
        assertEquals(PushMailboxOutbox.Result.Conflict, prepare(outbox, draft(peer = changedPeer)))
        assertEquals(PushMailboxOutbox.Result.Conflict, prepare(outbox, draft(state = epoch(generation = 2, epochHex = "cd".repeat(16)))))
        assertEquals(PushMailboxOutbox.Result.Rejected, prepare(outbox, draft(peer = changedPeer, state = epoch())))
        assertEquals(saved.entry.envelope, result(outbox).entry.envelope)
        assertEquals(1, seals.get())
        assertEquals(1, rawRows())
    }

    @Test fun invalidInputsOrCutoffsNeverSealOrInsert() {
        val outbox = store()
        val revoke = PushEpochProtocol.parse(PushEpochProtocol.encodeRevoke(
            2, PushEpochProtocol.bindingHash(peer), PushEpochProtocol.bindingHash(local),
        ))!!
        val revoked = (PushEpochProtocol.accept(revoke, true, peer, peer, local, null) as PushEpochProtocol.Verdict.Applied).state
        assertEquals(PushMailboxOutbox.Result.Rejected, prepare(outbox, draft(id = "bad")))
        assertEquals(PushMailboxOutbox.Result.Rejected, prepare(outbox, draft(content = "")))
        assertEquals(PushMailboxOutbox.Result.Rejected, prepare(outbox, draft(local = byteArrayOf(), state = epoch())))
        assertEquals(PushMailboxOutbox.Result.Rejected, prepare(outbox, draft(secret = ByteArray(32))))
        assertEquals(PushMailboxOutbox.Result.Rejected, prepare(outbox, draft(peer = local, state = epoch())))
        assertEquals(PushMailboxOutbox.Result.Rejected, prepare(outbox, draft(state = revoked)))
        assertEquals(PushMailboxOutbox.Result.Rejected, outbox.prepare(draft()) { false })
        assertEquals(PushMailboxOutbox.Result.Rejected, prepare(outbox, draft(cutoff = now)))
        assertEquals(PushMailboxOutbox.Result.Rejected, prepare(outbox, draft(cutoff = now + 7L * 86_400_000 + 1)))
        assertEquals(0, seals.get())
        assertEquals(0, rawRows())
    }

    @Test fun expiryIsTerminalAndRollbackOfWallClockNeverExtendsIt() {
        val outbox = store()
        val cutoff = now + 10_000L
        val saved = result(outbox, draft(cutoff = cutoff))
        now -= 8L * 86_400_000
        assertEquals(PushMailboxOutbox.Result.Unavailable, prepare(outbox, draft(cutoff = now + 1000)))
        now = cutoff - 1
        assertEquals(saved.entry.envelope, result(outbox).entry.envelope)
        now = cutoff
        assertEquals(PushMailboxOutbox.Result.Terminal, prepare(outbox, draft(cutoff = now + 86_400_000)))
        now -= 1_000
        assertEquals(PushMailboxOutbox.Result.Terminal, prepare(outbox))
        assertEquals(1, seals.get())
        assertEquals(1, rawRows())
    }

    @Test fun retiringIsCompareAndSetAndAStaleCallbackRollsBack() {
        val outbox = store()
        val entry = result(outbox).entry
        val wrongScope = PushMailboxOutbox.Entry(entry.recordKey, "00".repeat(32), entry.id, entry.expiresAt, entry.envelope)
        val wrongExpiry = PushMailboxOutbox.Entry(entry.recordKey, entry.scopeToken, entry.id, entry.expiresAt + 1, entry.envelope)
        assertEquals(PushMailboxOutbox.RetireResult.STALE, outbox.retire(wrongScope) { true })
        assertEquals(PushMailboxOutbox.RetireResult.STALE, outbox.retire(wrongExpiry) { true })
        var calls = 0
        assertEquals(PushMailboxOutbox.RetireResult.STALE, outbox.retire(entry) { ++calls == 1 })
        assertEquals(entry.envelope, result(outbox).entry.envelope)
        assertEquals(PushMailboxOutbox.RetireResult.RETIRED, outbox.retire(entry) { true })
        assertEquals(PushMailboxOutbox.RetireResult.ALREADY_TERMINAL, outbox.retire(entry) { true })
        assertEquals(PushMailboxOutbox.RetireResult.STALE, outbox.retire(wrongScope) { true })
        assertEquals(PushMailboxOutbox.Result.Terminal, prepare(outbox))
        assertEquals(1, seals.get())
    }

    @Test fun staleOrCancelledPrepareAfterInsertRollsBackTheTransaction() {
        val outbox = store()
        var calls = 0
        assertEquals(PushMailboxOutbox.Result.Rejected, outbox.prepare(draft()) { ++calls < 9 })
        assertEquals(9, calls) // the final check follows the tentative INSERT
        assertEquals(0, rawRows())
        calls = 0
        val exception = runCatching {
            outbox.prepare(draft()) {
                if (++calls == 9) throw CancellationException("test cancellation")
                true
            }
        }.exceptionOrNull()
        assertTrue(exception is CancellationException)
        assertEquals(9, calls)
        assertEquals(0, rawRows())
        assertTrue(result(outbox).created)
        assertEquals(1, rawRows())
    }

    @Test fun quotasNeverEvictTerminalHistoryOrPartiallyInsertLargeCiphertexts() {
        val outbox = store(maxRecords = 1, maxActiveBytes = 1024)
        assertEquals(PushMailboxOutbox.Result.Full, prepare(outbox, draft(content = "a".repeat(2_000))))
        assertEquals(0, rawRows())
        val entry = result(outbox, draft(content = "small")).entry
        val another = draft(id = "11234567-89ab-4cde-8f01-23456789abcd", content = "small")
        assertEquals(PushMailboxOutbox.Result.Full, prepare(outbox, another))
        assertEquals(PushMailboxOutbox.RetireResult.RETIRED, outbox.retire(entry) { true })
        assertEquals(PushMailboxOutbox.Result.Full, prepare(outbox, another))
        assertEquals(PushMailboxOutbox.Result.Terminal, prepare(outbox, draft(content = "small")))
        assertEquals(1, rawRows())
    }

    @Test fun boundedExpirySweepPreservesMarkersAndOtherActiveRecords() {
        val outbox = store()
        val ids = (1..5).map { "$it" + originalId.substring(1) }
        ids.forEachIndexed { index, id -> result(outbox, draft(id = id, cutoff = now + if (index < 3) 100 else 10_000)) }
        now += 100
        assertEquals(PushMailboxOutbox.SweepResult.Unavailable, outbox.retireExpired(0))
        assertEquals(2, (outbox.retireExpired(2) as PushMailboxOutbox.SweepResult.Completed).retired)
        assertEquals(1, (outbox.retireExpired(2) as PushMailboxOutbox.SweepResult.Completed).retired)
        assertEquals(0, (outbox.retireExpired(2) as PushMailboxOutbox.SweepResult.Completed).retired)
        ids.take(3).forEach { assertEquals(PushMailboxOutbox.Result.Terminal, prepare(outbox, draft(id = it))) }
        ids.drop(3).forEach { assertFalse(result(outbox, draft(id = it)).created) }
        assertEquals(5, rawRows())
        assertEquals(5, seals.get())
    }

    @Test fun corruptedPayloadOrUnauthenticatedExpiryCannotBeReplaced() {
        val outbox = store()
        result(outbox)
        outbox.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE mailbox_outbox SET expires_at = expires_at + 1")
        }
        val damaged = store()
        assertEquals(PushMailboxOutbox.Result.Unavailable, prepare(damaged))
        damaged.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE mailbox_outbox SET payload = zeroblob(32)")
        }
        val corrupted = store()
        assertEquals(PushMailboxOutbox.Result.Unavailable, prepare(corrupted))
        assertEquals(1, seals.get())
        assertEquals(1, rawRows())
        assertTrue(failures.get() >= 2)
    }

    @Test fun missingKeystoreKeyDoesNotRecreateItWhenRecordsExist() {
        val outbox = store()
        result(outbox)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertEquals(PushMailboxOutbox.Result.Unavailable, prepare(outbox))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        assertEquals(1, seals.get())
        assertEquals(1, rawRows())
    }
}
