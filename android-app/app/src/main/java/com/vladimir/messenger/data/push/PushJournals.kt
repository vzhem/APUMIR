package com.vladimir.messenger.data.push

import android.content.Context
import java.util.logging.Logger

/**
 * One lazily created set of encrypted journals for this process.
 *
 * The journals live in `noBackupFilesDir` behind Android Keystore keys, are
 * final classes without interfaces, and must never be duplicated: two instances
 * over the same SQLite file would each cache their own state. They are created
 * once here and shared by the Firebase service, the workers and any future UI.
 *
 * Creating them performs no network or provider call. Every journal fails
 * closed on a missing key, corruption or quota; this object does not change
 * that, it only decides which instance is asked.
 */
internal object PushJournals {
    private val LOCK = Any()
    private var cached: Entries? = null

    class Entries internal constructor(
        val registrations: PushRegistrationStore,
        val inbox: PushWakeInbox,
        val epochs: PushEpochStore,
        val tokens: FcmTokenJournal,
    )

    fun of(context: Context): Entries = synchronized(LOCK) {
        val app = context.applicationContext
        cached ?: Entries(
            registrations = PushRegistrationStore(app),
            inbox = PushWakeInbox(app),
            epochs = PushEpochStore(app),
            tokens = FcmTokenJournal(app),
        ).also { cached = it }
    }

    /**
     * Release this process's handles (app shutdown in tests, or a wiped
     * profile). A closed journal is reopened lazily on the next [of]; it never
     * deletes data and never touches the Keystore keys.
     */
    internal fun release() = synchronized(LOCK) {
        try {
            cached?.registrations?.close()
            cached?.inbox?.close()
            cached?.epochs?.close()
            cached?.tokens?.close()
        } catch (_: Exception) {
            Logger.getLogger("PushJournals").warning("Journal close failed")
        }
        cached = null
    }
}
