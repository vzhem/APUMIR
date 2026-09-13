package com.vladimir.messenger.data.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vladimir.messenger.BuildConfig
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.local.APP_DATABASE_VERSION
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Резервная копия профиля целиком и восстановление из неё.
 *
 * Зачем: «Защита личности» возвращает после переустановки только адрес и имя,
 * а чаты, контакты, сообщества, каналы, привязки шифрования и ранг пропадали.
 * Здесь всё это уезжает в один файл `.apubak`, который человек кладёт куда
 * хочет (SAF: «Файлы», флешка, облако), и возвращается из него на любом
 * телефоне по паролю.
 *
 * Раскладка архива - [BackupLayout], шифрование - [BackupCipher], манифест -
 * [BackupManifest]. Здесь - только сбор с диска и раскладывание обратно.
 *
 * Восстановление сделано в два шага, потому что нельзя подменять базу под
 * работающим приложением:
 *  1. [stage] - файл открывается паролем, проверяется манифест, содержимое
 *     распаковывается в служебную папку `noBackupFilesDir/restore_pending`
 *     (уже без шифрования, но в закрытой области приложения) и пишется
 *     маркер. Никаких изменений в живом состоянии.
 *  2. Приложение просит перезапуск. При следующем старте, до Room и сервисов,
 *     [applyStagedIfAny] кладёт файлы на места, заворачивает секреты ключом
 *     Keystore этого телефона, ставит метку устройства и удаляет служебную папку.
 */
@Singleton
class ProfileBackup @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: AppDatabase,
) {
    sealed interface CreateResult {
        data class Success(val bytes: Long, val receivedFiles: Int) : CreateResult
        data object NoIdentity : CreateResult
        data object BadPassword : CreateResult
        data class Failed(val reason: String) : CreateResult
    }

    sealed interface StageResult {
        /** Файл открыт и подготовлен; нужен перезапуск, чтобы применить. */
        data class Ready(val manifest: BackupManifest) : StageResult
        data object WrongPassword : StageResult
        data object NotBackupFile : StageResult
        /** Копия сделана более новой версией приложения. */
        data class TooNew(val appVersion: String) : StageResult
        data object Truncated : StageResult
        data class Failed(val reason: String) : StageResult
    }

    /** Есть ли подготовленная копия, ожидающая подтверждения и перезапуска. */
    fun hasStaged(): Boolean = File(stagingRoot(), STAGED_MARKER).isFile

    /**
     * Подтвердить: применить при следующем старте процесса. До подтверждения
     * подготовленная копия ни на что не влияет - случайный перезапуск
     * приложения не подменит профиль без спроса.
     */
    fun confirmStaged(): Boolean {
        if (!hasStaged()) return false
        return runCatching { File(stagingRoot(), READY_MARKER).writeText("v1"); true }.getOrDefault(false)
    }

    fun stagedManifest(): BackupManifest? =
        runCatching { BackupManifest.decode(File(stagingRoot(), BackupLayout.MANIFEST).readText()) }.getOrNull()

    fun discardStaged() {
        stagingRoot().deleteRecursively()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Создание
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Записать копию в [target] (SAF-URI из ACTION_CREATE_DOCUMENT). Долго:
     * вывод ключа, снимок базы и копирование файлов - звать не с главного потока.
     */
    fun create(target: Uri, password: CharArray, includeReceived: Boolean): CreateResult =
        create(password, includeReceived) {
            // «wt» = перезаписать с нуля; часть провайдеров документов понимает только «w».
            runCatching { appContext.contentResolver.openOutputStream(target, "wt") }.getOrNull()
                ?: appContext.contentResolver.openOutputStream(target, "w")
        }

    /** То же в обычный файл (служебный файл автообновления - [BackupSchedule]). */
    fun createFile(target: File, password: CharArray, includeReceived: Boolean): CreateResult =
        create(password, includeReceived) {
            target.parentFile?.mkdirs()
            FileOutputStream(target)
        }

    /** Перелить готовый файл копии в SAF-адрес человека. Возвращает число байт. */
    fun copyFileTo(source: File, target: Uri): Long {
        val raw = runCatching { appContext.contentResolver.openOutputStream(target, "wt") }.getOrNull()
            ?: appContext.contentResolver.openOutputStream(target, "w")
            ?: throw IOException("cannot open destination")
        var total = 0L
        BufferedOutputStream(raw, 1 shl 16).use { out ->
            FileInputStream(source).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                }
            }
            out.flush()
        }
        return total
    }

    /** Грубая оценка размера копии сверху (база + файлы, без сжатия). */
    fun estimateBytes(includeReceived: Boolean): Long {
        var total = 0L
        val db = appContext.getDatabasePath("messenger_database")
        listOf("", "-wal").forEach { total += File(db.path + it).length() }
        avatarDir().listFiles()?.forEach { if (it.isFile) total += it.length() }
        previewDir().listFiles()?.forEach { if (it.isFile) total += it.length() }
        if (includeReceived) total += listReceived().sumOf { it.second.length() }
        return total
    }

    /** Две копии разом (руками и по расписанию) делили бы один снимок базы - по очереди. */
    private val createLock = Any()

    private fun create(password: CharArray, includeReceived: Boolean, open: () -> OutputStream?): CreateResult =
        synchronized(createLock) { createLocked(password, includeReceived, open) }

    private fun createLocked(password: CharArray, includeReceived: Boolean, open: () -> OutputStream?): CreateResult {
        if (password.size < BackupCipher.MIN_PASSWORD_LENGTH) return CreateResult.BadPassword
        val prefs = appContext.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("identity_created", false)) return CreateResult.NoIdentity
        val nodeId = prefs.getString("node_id", null) ?: prefs.getString("existing_public_key", null)
            ?: return CreateResult.NoIdentity

        val snapshot = try {
            snapshotDatabase()
        } catch (e: Exception) {
            Log.w(TAG, "database snapshot failed: ${e.javaClass.simpleName}: ${e.message}")
            return CreateResult.Failed("database snapshot: ${e.message ?: e.javaClass.simpleName}")
        }
        try {
            val received = if (includeReceived) listReceived() else emptyList()
            val manifest = BackupManifest(
                dbVersion = APP_DATABASE_VERSION,
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                createdAtMs = System.currentTimeMillis(),
                nodeId = nodeId,
                displayName = prefs.getString("display_name", "") ?: "",
                nickname = prefs.getString("my_username", "") ?: "",
                includesReceived = includeReceived,
                receivedFiles = received.size,
                receivedBytes = received.sumOf { it.second.length() },
            )
            val raw = open() ?: return CreateResult.Failed("cannot open destination")
            val counting = CountingOutputStream(BufferedOutputStream(raw, 1 shl 16))
            ZipOutputStream(BackupCipher.encrypt(counting, password)).use { zip ->
                // Архив внутри шифра: сжатие включено (текст базы и настроек ужимается заметно).
                putText(zip, BackupLayout.MANIFEST, manifest.encode())
                for (name in BackupLayout.PREFS_NAMES) {
                    val entries = readPrefs(name) ?: continue
                    putText(zip, BackupLayout.prefsEntry(name), PrefsCodec.encode(entries))
                }
                exportSecrets(zip)
                putFile(zip, BackupLayout.DB, snapshot)
                val wal = File(snapshot.path + "-wal")
                if (wal.isFile && wal.length() > 0) putFile(zip, BackupLayout.DB_WAL, wal)
                avatarDir().listFiles()?.filter { it.isFile && BackupLayout.isSafeName(it.name) }?.forEach { file ->
                    putFile(zip, BackupLayout.AVATAR_DIR + file.name, file)
                }
                previewDir().listFiles()?.filter { it.isFile }?.forEach { file ->
                    val id = file.name.removeSuffix(".jpg")
                    if (file.name.endsWith(".jpg") && BackupLayout.isTransferId(id)) {
                        putFile(zip, BackupLayout.PREVIEW_DIR + file.name, file)
                    }
                }
                for ((id, file) in received) {
                    putFile(zip, BackupLayout.RECEIVED_DIR + id + "/" + file.name, file)
                }
            }
            Log.i(TAG, "backup written: ${counting.count} bytes, received=${received.size}, db=${snapshot.length()}")
            return CreateResult.Success(counting.count, received.size)
        } catch (e: Exception) {
            Log.w(TAG, "backup failed: ${e.javaClass.simpleName}: ${e.message}")
            return CreateResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(snapshot.path + it).delete() }
        }
    }

    /**
     * Согласованный снимок базы. На Android 10+ (SQLite ≥ 3.28) - `VACUUM INTO`:
     * один снимок одной транзакцией, WAL уже влит, файл компактный. На Android
     * 8–9 такой команды ещё нет: там checkpoint и копия файла базы вместе с
     * остатком WAL (если кто-то успел записать между ними - хвост в WAL).
     */
    private fun snapshotDatabase(): File {
        val dir = File(appContext.noBackupFilesDir, SNAPSHOT_DIR).apply { mkdirs() }
        val out = File(dir, "messenger_database")
        listOf("", "-wal", "-shm", "-journal").forEach { File(out.path + it).delete() }
        val db: SupportSQLiteDatabase = database.openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val escaped = out.absolutePath.replace("'", "''")
            db.execSQL("VACUUM INTO '$escaped'")
            check(out.isFile && out.length() > 0) { "database snapshot is empty" }
            return out
        }
        val source = appContext.getDatabasePath("messenger_database")
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        // Пока копируем, держим блокировку записи: никто не допишет в базу на полпути.
        db.beginTransaction()
        try {
            source.copyTo(out, overwrite = true)
            val wal = File(source.path + "-wal")
            if (wal.isFile && wal.length() > 0) wal.copyTo(File(out.path + "-wal"), overwrite = true)
        } finally {
            db.endTransaction()
        }
        return out
    }

    private fun exportSecrets(zip: ZipOutputStream) {
        // Секреты в открытом виде - только внутри зашифрованного файла; см. BackupLayout.
        runCatching {
            FileExchangeKeyStore.withExistingSecret(appContext) { secret ->
                putBytes(zip, BackupLayout.SECRET_FILE_EXCHANGE, secret)
            }
        }.onFailure { Log.i(TAG, "file exchange secret not exported: ${it.message}") }
        // withSeed создал бы seed, если его нет; в копию кладём только существующий.
        if (IdentitySigningKeyStore.mode(appContext) == IdentitySigningKeyStore.Mode.READY) {
            runCatching {
                IdentitySigningKeyStore.withSeed(appContext) { seed ->
                    putBytes(zip, BackupLayout.SECRET_SIGNING_SEED, seed)
                }
            }.onFailure { Log.i(TAG, "signing seed not exported: ${it.message}") }
        }
    }

    private fun readPrefs(name: String): Map<String, *>? {
        val file = File(File(appContext.applicationInfo.dataDir, "shared_prefs"), "$name.xml")
        if (!file.isFile) return null
        val all = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all
        val skip = BackupLayout.PREFS_KEYS_NOT_PORTABLE[name].orEmpty()
        return if (skip.isEmpty()) all else all.filterKeys { it !in skip }
    }

    /** Полученные файлы: `file_received/v1/<transferId>/<имя>` (только законченные, по одному файлу). */
    private fun listReceived(): List<Pair<String, File>> {
        val root = File(appContext.noBackupFilesDir, RECEIVED_ROOT)
        val dirs = root.listFiles() ?: return emptyList()
        val out = ArrayList<Pair<String, File>>()
        for (dir in dirs) {
            if (!dir.isDirectory || !BackupLayout.isTransferId(dir.name)) continue
            dir.listFiles()?.filter { it.isFile && !it.name.startsWith('.') && BackupLayout.isSafeName(it.name) }
                ?.forEach { out += dir.name to it }
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────────────
    // Восстановление, шаг 1: подготовка
    // ─────────────────────────────────────────────────────────────────────

    /** Открыть файл паролем и распаковать в служебную папку. Долго - не с главного потока. */
    fun stage(source: Uri, password: CharArray): StageResult {
        val root = stagingRoot()
        root.deleteRecursively()
        root.mkdirs()
        val result = stageInto(root, source, password)
        if (result !is StageResult.Ready) {
            root.deleteRecursively()
        } else {
            runCatching { File(root, STAGED_MARKER).writeText("v1") }
        }
        return result
    }

    private fun stageInto(root: File, source: Uri, password: CharArray): StageResult {
        var manifest: BackupManifest? = null
        try {
            val raw = appContext.contentResolver.openInputStream(source)
                ?: return StageResult.Failed("cannot open file")
            val decrypting = BackupCipher.decrypt(BufferedInputStream(raw, 1 shl 16), password)
            ZipInputStream(decrypting).use { zip ->
                var first = true
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (first) {
                        // Манифест обязан идти первым: по нему решаем, разбирать ли остальное.
                        first = false
                        if (entry.name != BackupLayout.MANIFEST) return StageResult.NotBackupFile
                        val text = String(readLimited(zip, MAX_MANIFEST_BYTES), Charsets.UTF_8)
                        val parsed = BackupManifest.decode(text) ?: return StageResult.NotBackupFile
                        if (parsed.format > BackupManifest.FORMAT || parsed.dbVersion > APP_DATABASE_VERSION) {
                            return StageResult.TooNew(parsed.appVersionName)
                        }
                        manifest = parsed
                        File(root, BackupLayout.MANIFEST).writeText(text)
                        continue
                    }
                    val kind = BackupLayout.classify(entry.name)
                    if (kind == null || kind == BackupLayout.Entry.Manifest || entry.isDirectory) {
                        Log.i(TAG, "restore: skipping entry ${entry.name.take(80)}")
                        continue
                    }
                    val target = File(root, entry.name)
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.copyTo(out, 1 shl 16) }
                }
                // ZIP останавливается на оглавлении и дальше не читает. Дочитываем
                // шифропоток до конца сами: только заверенная последняя порция
                // доказывает, что файл не обрезан (обрыв ровно на границе записи
                // ZipInputStream принял бы за конец архива).
                val sink = ByteArray(8192)
                while (decrypting.read(sink) != -1) { /* только проверка целостности */ }
            }
        } catch (e: BackupCipher.WrongPasswordException) {
            return StageResult.WrongPassword
        } catch (e: BackupCipher.UnsupportedFormatException) {
            return if (e.newer) StageResult.TooNew("") else StageResult.NotBackupFile
        } catch (e: EOFException) {
            return StageResult.Truncated
        } catch (e: Exception) {
            Log.w(TAG, "restore staging failed: ${e.javaClass.simpleName}: ${e.message}")
            return StageResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        val m = manifest ?: return StageResult.NotBackupFile
        if (!File(root, BackupLayout.DB).isFile) return StageResult.Failed("database missing in backup")
        Log.i(TAG, "restore staged: node=${m.nodeId.take(12)}… db=${m.dbVersion} app=${m.appVersionName}")
        return StageResult.Ready(m)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Восстановление, шаг 2: применение на старте процесса
    // ─────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "ProfileBackup"
        private const val MAIN_PREFS = "p2p_prefs"
        private const val STAGING_DIR = "restore_pending"
        private const val SNAPSHOT_DIR = "backup_snapshot"
        private const val STAGED_MARKER = ".staged"
        private const val READY_MARKER = ".ready"
        private const val RECEIVED_ROOT = "file_received/v1"
        private const val PREVIEW_ROOT = "file_preview/v1"
        private const val MAX_MANIFEST_BYTES = 16 * 1024

        /**
         * Применить подготовленную копию, если она есть. Зовётся из
         * `MessengerApplication.onCreate` ДО всего остального: Room ещё не
         * открыт, сервисы не запущены, поэтому файлы можно класть на места.
         * Возвращает true, если состояние заменено.
         */
        fun applyStagedIfAny(context: Context): Boolean {
            val app = context.applicationContext
            val root = File(app.noBackupFilesDir, STAGING_DIR)
            if (!File(root, READY_MARKER).isFile) return false
            return try {
                applyStaged(app, root)
                true
            } catch (e: Exception) {
                Log.e(TAG, "restore apply failed", e)
                false
            } finally {
                root.deleteRecursively()
            }
        }

        private fun applyStaged(app: Context, root: File) {
            val manifest = BackupManifest.decode(File(root, BackupLayout.MANIFEST).readText())
                ?: throw IOException("staged manifest unreadable")
            if (manifest.dbVersion > APP_DATABASE_VERSION) throw IOException("staged backup is newer than app")

            // 1. База: старую убираем целиком (вместе с WAL/SHM), новую кладём.
            val dbFile = app.getDatabasePath("messenger_database")
            app.deleteDatabase("messenger_database")
            moveFile(File(root, BackupLayout.DB), dbFile)
            val wal = File(root, BackupLayout.DB_WAL)
            if (wal.isFile) moveFile(wal, File(dbFile.path + "-wal"))
            failInFlightTransfers(dbFile)

            // 2. Настройки: каждый файл целиком заменяем содержимым копии;
            //    непереносимые (завёрнутые Keystore) ключи чистим - их перепишут секреты.
            for (name in BackupLayout.PREFS_NAMES) {
                val file = File(root, BackupLayout.prefsEntry(name))
                if (!file.isFile) continue
                val entries = PrefsCodec.decode(file.readText())
                if (entries == null) {
                    Log.w(TAG, "restore: prefs $name damaged, skipped")
                    continue
                }
                val editor = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                val skip = BackupLayout.PREFS_KEYS_NOT_PORTABLE[name].orEmpty()
                for ((key, value) in entries) {
                    if (key in skip) continue
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                editor.commit()
            }
            // Ключ ретрансляционного хранилища принадлежал старому телефону.
            app.getSharedPreferences("apu_relay_at_rest", Context.MODE_PRIVATE).edit().clear().commit()
            // Автообновление копии смотрело в файл ПРЕЖНЕГО профиля этого
            // телефона: другой профиль туда писать нельзя - выключаем с пояснением.
            runCatching { BackupSchedule.resetAfterRestore(app) }
            listOf(
                "apu_relay.sqlite", "apu_relay.sqlite-wal", "apu_relay.sqlite-shm",
                "apu_relay.sqlite.relay.sqlite", "apu_relay.sqlite.relay.sqlite-wal", "apu_relay.sqlite.relay.sqlite-shm",
            ).forEach { File(app.filesDir, it).delete() }

            // 3. Секреты: заворачиваем ключом Keystore этого телефона.
            val fx = File(root, BackupLayout.SECRET_FILE_EXCHANGE)
            if (fx.isFile) {
                val secret = fx.readBytes()
                val ok = FileExchangeKeyStore.importSecret(app, secret)
                secret.fill(0)
                if (!ok) {
                    // Без секрета подписанная привязка бесполезна и даже вредна (не совпадёт).
                    app.getSharedPreferences("apu_file_exchange", Context.MODE_PRIVATE).edit().clear().commit()
                    Log.w(TAG, "restore: file exchange secret not imported, binding dropped")
                }
            } else {
                app.getSharedPreferences("apu_file_exchange", Context.MODE_PRIVATE).edit().clear().commit()
            }
            val seedFile = File(root, BackupLayout.SECRET_SIGNING_SEED)
            if (seedFile.isFile) {
                val seed = seedFile.readBytes()
                val ok = IdentitySigningKeyStore.importSeed(app, seed)
                seed.fill(0)
                if (!ok) {
                    app.getSharedPreferences("apu_identity_signing", Context.MODE_PRIVATE).edit().clear().commit()
                    Log.w(TAG, "restore: signing seed not imported, binding dropped")
                }
            } else {
                app.getSharedPreferences("apu_identity_signing", Context.MODE_PRIVATE).edit().clear().commit()
            }

            // 4. Файлы: аватары, превью, полученные.
            val avatar = File(root, BackupLayout.AVATAR_DIR)
            if (avatar.isDirectory) {
                val dst = File(app.filesDir, "avatar")
                avatar.listFiles()?.filter { it.isFile }?.forEach { moveFile(it, File(dst, it.name)) }
            }
            val preview = File(root, BackupLayout.PREVIEW_DIR)
            if (preview.isDirectory) {
                val dst = File(app.noBackupFilesDir, PREVIEW_ROOT)
                preview.listFiles()?.filter { it.isFile }?.forEach { moveFile(it, File(dst, it.name)) }
            }
            val received = File(root, BackupLayout.RECEIVED_DIR)
            if (received.isDirectory) {
                val dst = File(app.noBackupFilesDir, RECEIVED_ROOT)
                received.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                    val target = File(dst, dir.name)
                    dir.listFiles()?.filter { it.isFile }?.forEach { moveFile(it, File(target, it.name)) }
                }
            }

            // 5. Метка устройства: без неё восстановленное состояние сочли бы
            //    подсунутым из чужого Auto Backup и стёрли на этом же запуске.
            DeviceIdentityMarker.create(app)
            Log.i(TAG, "restore applied: node=${manifest.nodeId.take(12)}… from app ${manifest.appVersionName}")
        }

        /**
         * Служебная папка и места назначения лежат на одном разделе, поэтому
         * переименование мгновенно даже для гигабайтов полученных файлов;
         * копирование - запасной путь.
         */
        private fun moveFile(src: File, dst: File) {
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            if (!src.renameTo(dst)) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
        }

        /**
         * Незаконченные передачи файлов из копии на новом телефоне продолжить
         * нельзя: их куски и пофайловые ключи (Keystore) остались на старом.
         * Помечаем их неудавшимися сразу, в сырой базе до Room, иначе насос
         * передач каждые 20 с спотыкался бы о пропавшие манифесты.
         */
        private fun failInFlightTransfers(dbFile: File) {
            runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                ).use { db ->
                    val hasTable = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'file_transfers'", null,
                    ).use { it.moveToFirst() }
                    if (!hasTable) return@use
                    db.execSQL(
                        "UPDATE file_transfers SET state = 'FAILED', errorCode = 'RESTORED_ELSEWHERE', " +
                            "updatedAtMs = ? WHERE state NOT IN ('COMPLETE', 'FAILED', 'CANCELLED', 'EXPIRED')",
                        arrayOf<Any>(System.currentTimeMillis()),
                    )
                }
            }.onFailure { Log.w(TAG, "restore: in-flight transfers not marked: ${it.message}") }
        }
    }

    private fun stagingRoot() = File(appContext.noBackupFilesDir, STAGING_DIR)
    private fun avatarDir() = File(appContext.filesDir, "avatar")
    private fun previewDir() = File(appContext.noBackupFilesDir, PREVIEW_ROOT)

    // ─────────────────────────────────────────────────────────────────────
    // ZIP-помощники
    // ─────────────────────────────────────────────────────────────────────

    private fun putText(zip: ZipOutputStream, name: String, text: String) =
        putBytes(zip, name, text.toByteArray(Charsets.UTF_8))

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zip, 1 shl 16) }
        zip.closeEntry()
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit + 1)
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        if (total > limit) throw IOException("manifest too large")
        return buffer.copyOf(total)
    }

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b); count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); count += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
