package com.vladimir.messenger.data.swarm

import android.content.Context
import android.os.StatFs
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Настройка «Место под пересылку»: сколько байт телефон отдаёт под чужие и
 * свои данные в пути (куски файлов, принятые копии до сохранения).
 *
 * Хранится в p2p_prefs под ключом storage_quota_bytes, как режим раздачи.
 * Читается хранилищем кусков при каждой записи (`FileTransferChunkStore`),
 * поэтому смена ползунка действует сразу, без перезапуска.
 *
 * Отдельно от квоты действует запас свободного места самого телефона
 * ([StoragePolicy.FREE_RESERVE_BYTES]): даже при «100 ГБ» на ползунке в
 * последние полгигабайта не залезаем. Только явная раздача обновлений в
 * `apkseed` обходит ползунок; системный запас остаётся обязательным.
 */
object StorageSettings {
    private const val PREFS = "p2p_prefs"
    private const val KEY_QUOTA = "storage_quota_bytes"

    private val _quotaBytes = MutableStateFlow(StoragePolicy.DEFAULT_QUOTA_BYTES)

    /** Выбранная квота, байт; всегда в пределах [StoragePolicy.MIN_QUOTA_BYTES]..[StoragePolicy.MAX_QUOTA_BYTES]. */
    val quotaBytes: StateFlow<Long> = _quotaBytes.asStateFlow()

    @Volatile
    private var initialized = false

    /** Прочитать сохранённую квоту. Безопасно звать многократно. */
    fun init(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _quotaBytes.value = StoragePolicy.clamp(prefs.getLong(KEY_QUOTA, StoragePolicy.DEFAULT_QUOTA_BYTES))
        initialized = true
    }

    /** Из настроек: пишет в prefs и действует сразу. */
    fun set(context: Context, bytes: Long) {
        val clamped = StoragePolicy.clamp(bytes)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_QUOTA, clamped).apply()
        _quotaBytes.value = clamped
        initialized = true
    }

    /** Квота прямо сейчас (для хранилища кусков: оно не подписывается, а спрашивает). */
    fun quota(context: Context): Long {
        init(context)
        return _quotaBytes.value
    }

    /**
     * Сколько ещё можно записать: остаток выбранной квоты и физического
     * запаса. Для явного `apkseed` квота владельца игнорируется, но запас
     * свободного места на разделе с данными приложения остаётся обязательным.
     */
    fun headroom(context: Context, usedBytes: Long, ownerQuotaExempt: Boolean = false): Long =
        StoragePolicy.headroomBytes(
            quotaBytes = quota(context),
            usedBytes = usedBytes,
            freeBytes = freeBytes(context),
            ownerQuotaExempt = ownerQuotaExempt,
        )

    /** Свободно на разделе, где лежат данные приложения; 0, если узнать не удалось. */
    fun freeBytes(context: Context): Long = try {
        val dir: File = context.applicationContext.noBackupFilesDir
        StatFs(dir.absolutePath).availableBytes
    } catch (_: Exception) {
        0L
    }

    /** Размер всех файлов под корнем (без переходов по ссылкам); 0 для отсутствующей папки. */
    fun sizeOfTree(root: File): Long {
        if (!root.exists()) return 0L
        var total = 0L
        root.walkTopDown()
            .onEnter { dir -> !java.nio.file.Files.isSymbolicLink(dir.toPath()) }
            .forEach { file ->
                if (file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                    total += file.length()
                }
            }
        return total
    }

    /**
     * Что телефон хранит сейчас как «сервер», по статьям. Считается обходом
     * папок - звать не с главного потока.
     */
    data class Usage(
        /** Зашифрованные куски файлов в пути (свои исходящие и входящие, дальше - чужие). */
        val chunkBytes: Long,
        /** Принятые расшифрованные файлы до сохранения владельцем в папку. */
        val receivedBytes: Long,
        /** Physical file lengths: relay SQLite + WAL + SHM, NOT live pending message bytes.
         * Includes indexes, reusable pages and WAL versions. Not governed by the chunk-store quota. */
        val relayBytes: Long,
        /** Мои копии файлов, приложенных к сообщениям сообществ, для раздачи (рой, этап 9). */
        val groupFileBytes: Long = 0L,
    ) {
        val total: Long get() = chunkBytes + receivedBytes + relayBytes + groupFileBytes
    }

    fun usage(context: Context): Usage {
        val app = context.applicationContext
        val chunks = sizeOfTree(File(app.noBackupFilesDir, "file_transfers/v1"))
        val received = sizeOfTree(File(app.noBackupFilesDir, "file_received/v1"))
        // Авторские копии файлов сообществ (GroupFileStore): живут неделю,
        // раздаются участникам по просьбе - тоже «данные в пути».
        val groupFiles = sizeOfTree(File(app.noBackupFilesDir, "group_files/v1"))
        var relay = 0L
        for (name in listOf("apu_relay.sqlite", "apu_relay.sqlite-wal", "apu_relay.sqlite-shm")) {
            val file = File(app.filesDir, name)
            if (file.isFile) relay += file.length()
        }
        return Usage(chunkBytes = chunks, receivedBytes = received, relayBytes = relay, groupFileBytes = groupFiles)
    }
}
