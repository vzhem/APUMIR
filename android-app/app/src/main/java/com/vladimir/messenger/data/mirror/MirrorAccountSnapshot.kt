package com.vladimir.messenger.data.mirror

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vladimir.messenger.data.local.AppDatabase
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Account-owned local state transferred after authenticated device pairing.
 *
 * The old initial mirror contract copied only [MirrorRow] messages, contacts,
 * profile and completed files. That is sufficient for a personal chat catch-up,
 * but it cannot recreate a group/channel, its members, topics, polls or local
 * reactions on a fresh launcher. This codec deliberately works below Room: all
 * account entities are already in one schema and copying the rows keeps new
 * columns from silently being forgotten when a feature is added.
 *
 * Device-specific secrets are intentionally not included. File bytes continue
 * to use the resumable file protocol, while the pairing frame itself is sealed
 * with the per-pair shared sync key before it leaves the device.
 */
object MirrorAccountSnapshot {
    const val VERSION = 1
    private const val TABLE_KEY = "table"
    private const val VALUES_KEY = "values"
    private const val BLOB_KEY = "__blob_b64"
    private const val PREF_TABLE_PREFIX = "@prefs:"
    private const val PREF_TYPE_KEY = "type"
    private const val PREF_VALUE_KEY = "value"
    private const val PREF_CHUNK_KEY = "chunk"
    private const val PREF_CHUNKS_KEY = "chunks"
    private const val PREF_CHUNK_CHARS = 16 * 1024
    /** Keep a single database row below the authenticated pairing envelope budget. */
    private const val MAX_ROW_CHARS = 32 * 1024
    private const val MAX_ROW_COLUMNS = 128

    /** Parent tables precede their group/message child tables. */
    private val ACCOUNT_TABLES = listOf(
        "contacts",
        "groups",
        "directory",
        "nicknames",
        "nickname_suppressions",
        "avatars",
        "peer_ranks",
        "file_transfers",
        "saved_items",
        "profile_hearts",
        "post_signers",
        "post_manifests",
        "post_views",
        "group_members",
        "group_topics",
        "group_invites",
        "group_join_requests",
        "group_message_stats",
        "group_polls",
        "group_poll_votes",
        "messages",
        "message_reactions",
        "message_reposts",
    )

    /** Presence and local rate-limit fields must not overwrite this device. */
    private val SNAPSHOT_EXCLUDED_COLUMNS = mapOf(
        "contacts" to setOf("isOnline", "lastSeen", "lastSeenAtMs"),
        "groups" to setOf("sosLastSentAtMs"),
    )

    private data class Column(val name: String, val primaryKeyOrder: Int)

    /** Export rows in deterministic table order for bounded frame chunking. */
    fun exportRows(database: AppDatabase): List<JSONObject> =
        exportRows(database.openHelper.writableDatabase)

    /** Include account-owned score/status preferences, never identity or device keys. */
    fun exportRows(context: Context, database: AppDatabase): List<JSONObject> =
        exportRows(database).toMutableList().also { it += exportPreferenceRows(context) }

    fun exportRows(database: SupportSQLiteDatabase): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        val groupsAvailable = tableColumns(database, "groups").isNotEmpty()
        for (table in ACCOUNT_TABLES) {
            val columns = tableColumns(database, table)
                .filterNot { it.name in SNAPSHOT_EXCLUDED_COLUMNS[table].orEmpty() }
            if (columns.isEmpty()) continue
            if (table == "messages" && !groupsAvailable) continue
            val sql = if (table == "messages") {
                // Personal messages still travel through the existing MirrorRow
                // path, which resolves each device's local chat id by contact.
                // Only group/channel history has a stable account-owned chat id.
                "SELECT m.* FROM messages m INNER JOIN groups g ON g.id = m.chatId"
            } else {
                "SELECT * FROM `${table}`"
            }
            database.query(sql).use { cursor ->
                while (cursor.moveToNext()) {
                    val values = JSONObject()
                    for (column in columns) {
                        val index = cursor.getColumnIndex(column.name)
                        if (index >= 0) values.put(column.name, cursorValue(cursor, index))
                    }
                    result += JSONObject()
                        .put("v", VERSION)
                        .put(TABLE_KEY, table)
                        .put(VALUES_KEY, values)
                }
            }
        }
        return result
    }

    private fun exportPreferenceRows(context: Context): List<JSONObject> {
        val dir = java.io.File(context.applicationContext.applicationInfo.dataDir, "shared_prefs")
        val names = dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.filter { it == "apu_emoji_status" || it == "apu_heart_sync_v1" || it.startsWith("heart-game-v1-") }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            .orEmpty()
        val result = ArrayList<JSONObject>()
        names.sorted().forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                .filterKeys { !it.startsWith("__apu_mirror_") }
                .toSortedMap()
                .forEach { (key, value) ->
                    val encoded = encodePreferenceValue(value) ?: return@forEach
                    if (encoded.optString(PREF_TYPE_KEY) == "string") {
                        val text = encoded.optString(PREF_VALUE_KEY)
                        if (text.length > PREF_CHUNK_CHARS) {
                            val chunks = (text.length + PREF_CHUNK_CHARS - 1) / PREF_CHUNK_CHARS
                            repeat(chunks) { index ->
                                result += preferenceRow(
                                    name,
                                    key,
                                    JSONObject()
                                        .put(PREF_TYPE_KEY, "string")
                                        .put(PREF_VALUE_KEY, text.substring(
                                            index * PREF_CHUNK_CHARS,
                                            minOf(text.length, (index + 1) * PREF_CHUNK_CHARS),
                                        )),
                                    index,
                                    chunks,
                                )
                            }
                            return@forEach
                        }
                    }
                    result += preferenceRow(name, key, encoded, 0, 1)
                }
        }
        return result
    }

    private fun preferenceRow(
        name: String,
        key: String,
        encoded: JSONObject,
        chunk: Int,
        chunks: Int,
    ): JSONObject = JSONObject()
        .put("v", VERSION)
        .put(TABLE_KEY, PREF_TABLE_PREFIX + name)
        .put("key", key)
        .put(PREF_TYPE_KEY, encoded.optString(PREF_TYPE_KEY))
        .put(PREF_VALUE_KEY, encoded.opt(PREF_VALUE_KEY))
        .put(PREF_CHUNK_KEY, chunk)
        .put(PREF_CHUNKS_KEY, chunks)

    private fun encodePreferenceValue(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put(PREF_TYPE_KEY, "string").put(PREF_VALUE_KEY, value)
        is Boolean -> JSONObject().put(PREF_TYPE_KEY, "boolean").put(PREF_VALUE_KEY, value)
        is Int -> JSONObject().put(PREF_TYPE_KEY, "int").put(PREF_VALUE_KEY, value)
        is Long -> JSONObject().put(PREF_TYPE_KEY, "long").put(PREF_VALUE_KEY, value)
        is Float -> JSONObject().put(PREF_TYPE_KEY, "float").put(PREF_VALUE_KEY, value.toDouble())
        is Set<*> -> JSONObject().put(
            PREF_TYPE_KEY,
            "set",
        ).put(
            PREF_VALUE_KEY,
            JSONArray().apply { value.filterIsInstance<String>().forEach { put(it) } },
        )
        else -> null
    }

    /** Number of rows that the receiver can apply; used for the manifest. */
    fun rowCount(rows: List<JSONObject>): Int = rows.size

    /** Apply one or more chunks without replacing parent rows (REPLACE would cascade-delete children). */
    fun applyRows(database: AppDatabase, rows: List<JSONObject>): Int =
        applyRows(database.openHelper.writableDatabase, rows)

    fun applyRows(context: Context, database: AppDatabase, rows: List<JSONObject>): Int =
        applyRows(context, database.openHelper.writableDatabase, rows)

    fun applyRows(database: SupportSQLiteDatabase, rows: List<JSONObject>): Int {
        return applyRows(null, database, rows)
    }

    private fun applyRows(context: Context?, database: SupportSQLiteDatabase, rows: List<JSONObject>): Int {
        if (rows.isEmpty()) return 0
        var applied = 0
        database.beginTransaction()
        try {
            // A transport retry may deliver a chunk out of order. Sorting by the
            // same parent-first order makes group/member foreign keys succeed
            // whenever both rows are in this chunk.
            rows.sortedBy { ACCOUNT_TABLES.indexOf(it.optString(TABLE_KEY)).coerceAtLeast(0) }
                .forEach {
                    val ok = if (it.optString(TABLE_KEY).startsWith(PREF_TABLE_PREFIX)) {
                        context?.let { app -> applyPreferenceRow(app, it) } ?: false
                    } else {
                        applyRow(database, it)
                    }
                    if (ok) applied++
                }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return applied
    }

    private fun applyPreferenceRow(context: Context, packet: JSONObject): Boolean {
        if (packet.toString().length > MAX_ROW_CHARS) return false
        val table = packet.optString(TABLE_KEY)
        val name = table.removePrefix(PREF_TABLE_PREFIX)
        val key = packet.optString("key")
        val type = packet.optString(PREF_TYPE_KEY)
        if (name.isBlank() || key.isBlank() || type.isBlank()) return false
        if (name != "apu_emoji_status" && name != "apu_heart_sync_v1" && !name.startsWith("heart-game-v1-")) return false
        val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val chunks = packet.optInt(PREF_CHUNKS_KEY, 1).coerceAtLeast(1)
        val chunk = packet.optInt(PREF_CHUNK_KEY, 0)
        val rawValue = packet.opt(PREF_VALUE_KEY)
        if (chunks > 1) {
            if (chunk !in 0 until chunks || type != "string" || rawValue !is String) return false
            val prefix = "__apu_mirror_${prefKeyHash(key)}_"
            val editor = prefs.edit()
                .putInt(prefix + "count", chunks)
                .putString(prefix + chunk, rawValue)
            if (!editor.commit()) return false
            val current = prefs.all
            if ((0 until chunks).all { current[prefix + it] is String }) {
                val joined = buildString {
                    for (index in 0 until chunks) append(current[prefix + index] as String)
                }
                val clean = prefs.edit().putString(key, joined)
                for (index in 0 until chunks) clean.remove(prefix + index)
                clean.remove(prefix + "count")
                return clean.commit()
            }
            return true
        }
        val editor = prefs.edit()
        when (type) {
            "string" -> editor.putString(key, rawValue?.toString().orEmpty())
            "boolean" -> editor.putBoolean(key, rawValue == true)
            "int" -> editor.putInt(key, (rawValue as? Number)?.toInt() ?: return false)
            "long" -> editor.putLong(key, (rawValue as? Number)?.toLong() ?: return false)
            "float" -> editor.putFloat(key, (rawValue as? Number)?.toFloat() ?: return false)
            "set" -> {
                val array = rawValue as? JSONArray ?: return false
                val set = buildSet {
                    for (index in 0 until array.length()) add(array.optString(index))
                }
                editor.putStringSet(key, set)
            }
            else -> return false
        }
        return editor.commit()
    }

    private fun prefKeyHash(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)

    private fun applyRow(database: SupportSQLiteDatabase, packet: JSONObject): Boolean {
        if (packet.toString().length > MAX_ROW_CHARS) return false
        val table = packet.optString(TABLE_KEY)
        if (table !in ACCOUNT_TABLES || packet.optInt("v", VERSION) > VERSION) return false
        val source = packet.optJSONObject(VALUES_KEY) ?: return false
        if (source.length() > MAX_ROW_COLUMNS) return false
        val columns = tableColumns(database, table)
        if (columns.isEmpty()) return false

        val values = ContentValues()
        columns.forEach { column ->
            if (source.has(column.name)) {
                putContentValue(values, column.name, source.opt(column.name))
            }
        }
        val primary = columns.filter { it.primaryKeyOrder > 0 }
            .sortedBy { it.primaryKeyOrder }
        if (primary.isEmpty()) {
            return database.insert(table, SQLiteDatabase.CONFLICT_IGNORE, values) != -1L
        }

        val where = primary.joinToString(" AND ") { "`${it.name}` = ?" }
        val args = primary.map { sqlArg(source.opt(it.name)) }
        if (args.any { it == null }) return false
        val whereArgs = args.map { it!! }.toTypedArray()
        return try {
            // UPDATE first is important. SQLite REPLACE deletes the old parent
            // row before inserting it and Room foreign keys then erase members,
            // topics and polls by cascade.
            val updated = database.update(table, SQLiteDatabase.CONFLICT_ABORT, values, where, whereArgs)
            if (updated > 0) {
                true
            } else {
                database.insert(table, SQLiteDatabase.CONFLICT_IGNORE, values) != -1L
            }
        } catch (_: Exception) {
            // One malformed/old row must not abort the entire account snapshot.
            false
        }
    }

    private fun tableColumns(database: SupportSQLiteDatabase, table: String): List<Column> {
        if (table !in ACCOUNT_TABLES) return emptyList()
        val result = ArrayList<Column>()
        database.query("PRAGMA table_info('$table')").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val pkIndex = cursor.getColumnIndex("pk")
            if (nameIndex < 0 || pkIndex < 0) return@use
            while (cursor.moveToNext()) {
                result += Column(cursor.getString(nameIndex), cursor.getInt(pkIndex))
            }
        }
        return result
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> JSONObject().put(
            BLOB_KEY,
            Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP),
        )
        else -> cursor.getString(index)
    }

    private fun putContentValue(values: ContentValues, key: String, raw: Any?) {
        when (raw) {
            null, JSONObject.NULL -> values.putNull(key)
            is JSONObject -> {
                val encoded = raw.optString(BLOB_KEY, "")
                if (encoded.isBlank()) values.putNull(key)
                else values.put(key, Base64.decode(encoded, Base64.NO_WRAP))
            }
            is Boolean -> values.put(key, if (raw) 1L else 0L)
            is Number -> {
                val text = raw.toString()
                val integer = text.toLongOrNull()
                if (integer != null) values.put(key, integer)
                else values.put(key, text.toDouble())
            }
            else -> values.put(key, raw.toString())
        }
    }

    private fun sqlArg(raw: Any?): String? = when (raw) {
        null, JSONObject.NULL -> null
        is JSONObject -> null // primary keys in the Room schema are text/integer, never blobs
        is Boolean -> if (raw) "1" else "0"
        else -> raw.toString()
    }
}
