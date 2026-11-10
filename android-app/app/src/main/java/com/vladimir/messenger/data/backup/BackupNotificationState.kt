package com.vladimir.messenger.data.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Pending notifications are device-local work, not message history. Sanitize the
 * staged copy (including its WAL) before either merge or replacement, while the
 * live database is still untouched. Older backups may not have this column.
 */
internal object BackupNotificationState {
    fun clearFromStaged(stagedDatabase: File) {
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
        }
    }
}
