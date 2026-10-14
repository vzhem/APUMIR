package com.vladimir.messenger.data.schedule

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real SQLite + Android Keystore checks. Does not change the app's identity or chat DB. */
@RunWith(AndroidJUnit4::class)
class DeferredInboxStorageInstrumentedTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val tag = UUID.randomUUID().toString()
    private val alias = "apu_deferred_test_$tag"
    private val prefName = "deferred_test_$tag"
    private val root = File(app.noBackupFilesDir, "deferred-test-$tag")
    private val database = File(root, "inbox.sqlite")
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            app.getSharedPreferences(if (name == "p2p_prefs") prefName else name, mode)
    }
    private lateinit var storage: DeferredInboxStorage
    private val letter = DeferredWire.Letter("deferred_test", 1_900_000_000_000L, "", "", "", "Скрытый секрет | 🕒")
    private val sender = "pk_sender0001"

    @Before fun setUp() {
        root.mkdirs()
        context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).edit().putString("node_id", "pk_owner00001").commit()
        storage = DeferredInboxStorage(alias, database)
    }

    @After fun tearDown() {
        storage.close()
        app.deleteSharedPreferences(prefName)
        root.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test fun cancellationBeforeDataSurvivesReopenAndSuppressesLateCopiesAndParts() {
        val cancel = DeferredWire.parseCancel(DeferredWire.cancel(letter))!!
        assertTrue(storage.cancel(context, sender, cancel))
        val key = storage.entryKey(context, sender, letter.id)
        assertEquals(listOf(key to 0L), storage.pending(context))
        assertNotNull(storage.cancellation(context, key))
        assertFalse(storage.put(context, sender, letter))
        assertTrue(storage.isCancelled(context, sender, "${letter.id}_part1"))
        storage.completeCancellation(context, key)
        storage.close()
        storage = DeferredInboxStorage(alias, database)
        assertTrue(storage.isCancelled(context, sender, letter.id))
        assertFalse(storage.put(context, sender, letter))
        assertTrue(storage.pending(context).isEmpty())
    }

    @Test fun cancellationReleasesHiddenPayloadAndOldTimerCannotUndoIt() {
        assertTrue(storage.put(context, sender, letter))
        val key = storage.entryKey(context, sender, letter.id)
        val cancel = DeferredWire.parseCancel(DeferredWire.cancel(letter))!!
        assertFalse(storage.cancel(context, sender, cancel.copy(digest = "0".repeat(64))))
        assertEquals(letter, storage.get(context, key)!!.letter)
        assertTrue(storage.cancel(context, sender, cancel))
        storage.complete(context, key) // stale reveal completion must not turn cancellation into normal completion
        assertNull(storage.get(context, key))
        assertTrue(storage.isCancelled(context, sender, letter.id))
        assertNotNull(storage.cancellation(context, key))
        assertFalse(storage.put(context, sender, letter))
    }

    @Test fun anotherSenderCannotCancelTheOriginalSendersHiddenCopy() {
        assertTrue(storage.put(context, sender, letter))
        val cancel = DeferredWire.parseCancel(DeferredWire.cancel(letter))!!
        assertTrue(storage.cancel(context, "pk_otherpeer01", cancel))
        assertFalse(storage.isCancelled(context, sender, letter.id))
        assertEquals(letter, storage.get(context, storage.entryKey(context, sender, letter.id))!!.letter)
    }

    @Test fun cancellationAfterRevealStillQueuesVisibleCleanupAndIsIdempotent() {
        assertTrue(storage.put(context, sender, letter))
        val key = storage.entryKey(context, sender, letter.id)
        storage.complete(context, key)
        val cancel = DeferredWire.parseCancel(DeferredWire.cancel(letter))!!
        assertTrue(storage.cancel(context, sender, cancel))
        assertNotNull(storage.cancellation(context, key))
        storage.completeCancellation(context, key)
        assertTrue(storage.cancel(context, sender, cancel))
        assertFalse(storage.put(context, sender, letter))
    }

    @Test fun committedHiddenCopySurvivesReopenWithoutAnyChatOrNetworkAccess() {
        assertTrue(storage.put(context, sender, letter))
        val key = storage.entryKey(context, sender, letter.id)
        storage.close()
        storage = DeferredInboxStorage(alias, database)
        assertEquals(letter, storage.get(context, key)!!.letter)
        assertEquals(listOf(key to letter.showAtMs), storage.pending(context))
        assertTrue(storage.owns(context, letter.id))
        // Check the actual database bytes, not a mocked serialization.
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT payload FROM inbox", null).use {
                assertTrue(it.moveToFirst())
                assertFalse(it.getBlob(0).toString(Charsets.UTF_8).contains(letter.text))
            }
        }
    }

    @Test fun duplicateIsIdempotentCollisionRejectedAndTombstonePreventsResurrection() {
        assertTrue(storage.put(context, sender, letter))
        assertTrue(storage.put(context, sender, letter))
        assertFalse(storage.put(context, sender, letter.copy(text = "Подмена")))
        assertEquals(1, storage.pending(context).size)
        val key = storage.entryKey(context, sender, letter.id)
        storage.complete(context, key)
        assertNull(storage.get(context, key))
        assertTrue(storage.put(context, sender, letter))
        assertTrue(storage.pending(context).isEmpty())
        assertTrue(storage.owns(context, letter.id))
    }

    @Test fun differentIdentityCannotReadOrRevealAnotherIdentitysPendingLetter() {
        assertTrue(storage.put(context, sender, letter))
        val key = storage.entryKey(context, sender, letter.id)
        context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).edit().putString("node_id", "pk_other00001").commit()
        assertNull(storage.get(context, key))
        assertTrue(storage.pending(context).isEmpty())
        assertFalse(storage.owns(context, letter.id))
        storage.complete(context, key) // cannot erase another owner's letter
        context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).edit().putString("node_id", "pk_owner00001").commit()
        assertEquals(letter, storage.get(context, key)!!.letter)
    }

    @Test fun tamperingFailsClosedAndDoesNotDiscardTheHiddenCopy() {
        assertTrue(storage.put(context, sender, letter))
        val key = storage.entryKey(context, sender, letter.id)
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE inbox SET payload=? WHERE entry_key=?", arrayOf(ByteArray(64), key))
        }
        assertTrue(runCatching { storage.get(context, key) }.isFailure)
        assertEquals(1, storage.pending(context).size)
    }

    @Test fun quotaRejectsWithoutEvictingPreviouslySavedMessages() {
        repeat(128) { assertTrue(storage.put(context, sender, letter.copy(id = "deferred_$it"))) }
        assertFalse(storage.put(context, sender, letter.copy(id = "deferred_excess")))
        assertEquals(128, storage.pending(context).size)
        assertEquals(letter.text, storage.get(context, storage.entryKey(context, sender, "deferred_0"))!!.letter.text)
    }
}
