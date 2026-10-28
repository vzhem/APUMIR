package com.vladimir.messenger.data.backup

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Дополнение живой базы содержимым резервной копии - без удаления чатов.
 *
 * Зачем. Полное восстановление раньше заменяло базу целиком: копия месячной
 * давности откатывала переписку на свою дату, а всё, что пришло позже, исчезало
 * с телефона (владелец 2026-10-28: «нажал восстановить от сентября, и все чаты
 * удалились»). Между тем в копии лежит только то, чего у нас может не хватать,
 * а не наоборот: свежие сообщения есть на телефоне, и терять их незачем.
 *
 * Правило слияния одно и простое: **живое главнее**. Строка копии добавляется,
 * только если такой строки на телефоне нет (совпадение по первичному ключу
 * или уникальному индексу - на этом и работает `INSERT OR IGNORE`). Поэтому:
 *  - чат, которого нет в копии, остаётся;
 *  - сообщение, пришедшее после даты копии, остаётся;
 *  - история из копии, которой на телефоне нет (переустановка, чистка),
 *    докачивается обратно;
 *  - переписка, изменённая на телефоне позже копии, не откатывается.
 *
 * Раскладку таблиц и колонок берём из самих файлов (`PRAGMA table_info`), а не
 * из Kotlin-сущностей: копия может быть сделана старой версией приложения, где
 * колонок меньше. Недостающие колонки заполняются значением по умолчанию,
 * колонки со своим DEFAULT просто не перечисляем - SQLite подставит его сам.
 *
 * Слияние идёт одной транзакцией: падение на любой таблице откатывает всё, и
 * база на телефоне остаётся точно такой, какой была. Планирование SQL вынесено
 * в чистую функцию [plan] - её проверяет обычный JVM-тест без Android.
 */
object BackupRestoreMerge {
    private const val TAG = "BackupRestoreMerge"

    /** Колонка таблицы: то, что нужно, чтобы построить вставку. */
    data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val hasDefault: Boolean,
    )

    data class Table(val name: String, val columns: List<Column>)

    /** Что сделать с одной таблицей при слиянии. */
    sealed interface Step {
        /** Вставить недостающие строки. */
        data class Insert(val table: String, val sql: String) : Step

        /** Пропустить таблицу и сказать почему (в лог и в отчёт). */
        data class Skip(val table: String, val reason: String) : Step
    }

    /**
     * Итог применения.
     *
     * [placedCopyAsDatabase] - живой базы не было (первый запуск), копия положена
     * целиком. [merged] - слияние прошло. Если [reason] непустой и [changed]
     * false, база на телефоне не тронута.
     */
    data class Outcome(
        val placedCopyAsDatabase: Boolean = false,
        val merged: Boolean = false,
        val insertedRows: Int = 0,
        val perTable: List<Pair<String, Int>> = emptyList(),
        val skipped: List<String> = emptyList(),
        val reason: String = "",
    ) {
        val changed: Boolean get() = placedCopyAsDatabase || insertedRows > 0

        /** Одна строка для журнала приложения: без имён чатов и текстов. */
        fun summary(): String = when {
            placedCopyAsDatabase -> "база взята из копии целиком"
            merged -> "дополнено таблиц=" + perTable.size + " строк=" + insertedRows
            else -> "база не изменена" + if (reason.isNotBlank()) ": " + reason else ""
        }
    }

    /**
     * Чистое планирование: какие вставки нужны, чтобы копия дополнила живую базу.
     *
     * Порядок таблиц - как в живой базе; внешние ключи при применении выключены,
     * поэтому порядок безопасен.
     */
    fun plan(live: List<Table>, copy: List<Table>): List<Step> {
        val copyByName = copy.associateBy { it.name }
        val steps = ArrayList<Step>(live.size)
        for (table in live) {
            if (isInternal(table.name)) continue
            val source = copyByName[table.name]
            if (source == null) {
                steps += Step.Skip(table.name, "в копии нет этой таблицы")
                continue
            }
            val available = source.columns.associateBy { it.name }
            val targets = table.columns.filter { !isInternal(it.name) }
            if (available.isEmpty()) {
                steps += Step.Skip(table.name, "в копии нет колонок")
                continue
            }
            // Колонка со своим DEFAULT и без пары в копии просто не перечисляется:
            // SQLite подставит умолчание, а не пустое значение.
            val written = targets.filter { available.containsKey(it.name) || !it.hasDefault }
            if (written.none { available.containsKey(it.name) }) {
                steps += Step.Skip(table.name, "нет общих колонок")
                continue
            }
            val columns = written.joinToString(", ") { quote(it.name) }
            val values = written.joinToString(", ") { column ->
                if (available.containsKey(column.name)) "copy." + quote(column.name) else literal(column)
            }
            steps += Step.Insert(
                table.name,
                "INSERT OR IGNORE INTO " + quote(table.name) +
                    " (" + columns + ") SELECT " + values +
                    " FROM copy." + quote(source.name),
            )
        }
        return steps
    }

    /** Слить копию в живую базу. Живая база остаётся главной. */
    fun merge(liveDb: File, stagedDb: File): Outcome {
        if (!stagedDb.isFile || stagedDb.length() <= 0L) {
            return Outcome(reason = "в копии нет базы")
        }
        if (!liveDb.isFile || liveDb.length() <= 0L) {
            return placeWholeCopy(liveDb, stagedDb)
        }
        return try {
            SQLiteDatabase.openDatabase(liveDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                .use { db ->
                    // Внешние ключи выключены: строки из копии могут ссылаться на
                    // записи, которых в копии нет (они есть на телефоне), и порядок
                    // вставки тогда не должен ничего ломать.
                    db.execSQL("PRAGMA foreign_keys=OFF")
                    db.execSQL("ATTACH DATABASE ? AS copy", arrayOf<Any>(stagedDb.absolutePath))
                    try {
                        applyInside(db, plan(readSchema(db, "main"), readSchema(db, "copy")))
                    } finally {
                        // Отсоединение - только уборка: если оно не удалось,
                        // транзакция уже закрыта, а файл копии закрывается сам
                        // при закрытии соединения. Глотаем осознанно.
                        runCatching { db.execSQL("DETACH DATABASE copy") }
                    }
                }
        } catch (error: Exception) {
            Log.w(TAG, "merge failed: " + describe(error))
            Outcome(reason = describe(error))
        }
    }

    private fun applyInside(db: SQLiteDatabase, steps: List<Step>): Outcome {
        val perTable = ArrayList<Pair<String, Int>>()
        val skipped = ArrayList<String>()
        var inserted = 0
        db.beginTransaction()
        try {
            for (step in steps) {
                when (step) {
                    is Step.Skip -> skipped += step.table + ": " + step.reason
                    is Step.Insert -> {
                        db.execSQL(step.sql)
                        val added = changedRows(db)
                        if (added > 0) {
                            inserted += added
                            perTable += step.table to added
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (skipped.isNotEmpty()) Log.i(TAG, "merge skipped: " + skipped.joinToString("; "))
        return Outcome(merged = true, insertedRows = inserted, perTable = perTable, skipped = skipped)
    }

    /**
     * Живой базы нет или она пустая - кладём копию как есть.
     *
     * Это первый запуск после переустановки: терять нечего, и слияние тут
     * свелось бы к переписыванию всех строк.
     */
    private fun placeWholeCopy(liveDb: File, stagedDb: File): Outcome = try {
        liveDb.parentFile?.mkdirs()
        stagedDb.copyTo(liveDb, overwrite = true)
        Outcome(placedCopyAsDatabase = true)
    } catch (error: Exception) {
        Log.w(TAG, "cannot place copy as live database: " + describe(error))
        Outcome(reason = describe(error))
    }

    private fun changedRows(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT changes()", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun readSchema(db: SQLiteDatabase, schema: String): List<Table> {
        val names = ArrayList<String>()
        db.rawQuery(
            "SELECT name FROM " + schema + ".sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (!name.isNullOrEmpty()) names += name
            }
        }
        return names.map { name -> Table(name, readColumns(db, schema, name)) }
    }

    private fun readColumns(db: SQLiteDatabase, schema: String, table: String): List<Column> {
        val columns = ArrayList<Column>()
        db.rawQuery("PRAGMA " + schema + ".table_info(" + quote(table) + ")", null).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            val typeIdx = cursor.getColumnIndex("type")
            val notNullIdx = cursor.getColumnIndex("notnull")
            val defaultIdx = cursor.getColumnIndex("dflt_value")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)
                if (name.isNullOrEmpty()) continue
                columns += Column(
                    name = name,
                    type = cursor.getString(typeIdx) ?: "",
                    notNull = cursor.getInt(notNullIdx) != 0,
                    hasDefault = !cursor.isNull(defaultIdx),
                )
            }
        }
        return columns
    }

    /** Значение для колонки, которой в копии нет и у которой нет своего DEFAULT. */
    private fun literal(column: Column): String {
        if (!column.notNull) return "NULL"
        val type = column.type.uppercase()
        return when {
            type.contains("INT") -> "0"
            type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB") -> "0"
            type.contains("BLOB") -> "x''"
            else -> "''"
        }
    }

    /** Служебные таблицы самой SQLite и Room: их не сливаем никогда. */
    private fun isInternal(name: String): Boolean =
        name.startsWith("sqlite_") ||
            name == "android_metadata" ||
            name == "room_master_table" ||
            name == "room_master"

    private fun isInternalColumn(name: String): Boolean = name.isEmpty()

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    private fun describe(error: Throwable): String {
        val message = error.message?.replace('\n', ' ')?.take(160)
        return error.javaClass.simpleName + (if (message != null) ": " + message else "")
    }
}
