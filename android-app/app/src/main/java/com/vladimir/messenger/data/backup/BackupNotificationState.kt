package com.vladimir.messenger.data.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Pending notifications are device-local work, not message history. Sanitize the
 * staged copy (including its WAL) before either merge or replacement, while the
 * live database is still untouched. Older backups may not have this column.
 *
 * A database file and its WAL are one snapshot. Before the caller moves or
 * copies only the database, force every committed WAL frame into that file and
 * fail closed if another reader prevents truncation. Otherwise moving just the
 * database could lose history or re-arm stale notification work. Staging is
 * private to startup and must not have concurrent connections or writers.
 */
internal object BackupNotificationState {
    fun clearFromStaged(stagedDatabase: File) {
        check(stagedDatabase.isFile && stagedDatabase.length() > 0L) { "Staged database missing" }
        SQLiteDatabase.openDatabase(stagedDatabase.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val hasColumn = db.rawQuery("PRAGMA table_info(`messages`)", null).use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(name) == "notificationPending") found = true
                }
                found
            }
            if (hasColumn) {
                db.execSQL("UPDATE messages SET notificationPending = 0 WHERE notificationPending <> 0")
            }
            // Even a legacy database without notificationPending may have
            // history in its WAL. TRUNCATE returns (0,0,0) for a completed WAL
            // or (0,-1,-1) when not using WAL; any busy/incomplete result fails.
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                check(cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1) && !cursor.isNull(2)) {
                    "Staged WAL checkpoint returned no status"
                }
                val busy = cursor.getInt(0)
                val frames = cursor.getInt(1)
                val applied = cursor.getInt(2)
                check(busy == 0 && frames == applied && (frames == 0 || frames == -1)) {
                    "Staged WAL checkpoint incomplete"
                }
            }
        }
        val wal = File(stagedDatabase.path + "-wal")
        check(!wal.exists() || (wal.isFile && wal.length() == 0L)) { "Staged WAL still contains data" }
    }
}
