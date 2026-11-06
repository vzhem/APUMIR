package com.vladimir.messenger.data.avatavideo

import android.content.Context
import android.util.Log
import com.vladimir.messenger.util.copyWithLimit
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Принятые видеоаватары собеседников: файл ролика и выбранная им форма.
 *
 * Один владелец — один текущий ролик. Объявление (см. [AvatarVideoWire])
 * кладёт запись «чего ждём», приехавший файл ставит её на место с проверкой
 * sha256 и размера. Слой под роликом — постер (обычный аватар), поэтому без
 * файла интерфейс просто показывает картинку: правило «ничего не ломать, нечего
 * показывать» действует и здесь.
 *
 * Объём ограничен: кольца «включить» не бывает бесплатным, а аватары бывают у
 * сотен контактов. [sweep] держит и число файлов, и суммарные байты, выгоняя
 * давние ролики (постер остаётся).
 */
object PeerVideoAvatarStore {
    private const val TAG = "PeerVideoAvatarStore"
    private const val PREFS = "peer-video-avatars-v1"
    private const val COUNTS = "peer-video-avatars-counters-v1"
    private const val DIR = "peer-video-avatars-v1"
    private const val MAX_FILES = 200
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    // Защищены монитором этого объекта. После перезапуска набор пуст, поэтому
    // sweep удалит оставшиеся после сбоя временные копии, не трогая активные.
    private val activeParts = mutableSetOf<String>()

    /** Чего ждём и что стоит: sha, размер, круг/квадрат, объявление и последний показ. */
    data class Meta(
        val ownerId: String,
        val sha256: String,
        val bytes: Long,
        val circle: Boolean,
        val updatedAtMs: Long,
        val lastSeenMs: Long,
    )

    /** Поколение данных: Compose перечитывает файл/круг по этому счётчику. */
    private val _changed = MutableStateFlow(0L)
    val changed = _changed.asStateFlow()

    fun metaFor(context: Context, ownerId: String): Meta? {
        if (ownerId.isBlank()) return null
        val raw = prefs(context).getString(ownerId, null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 5) return null
        val bytes = parts[1].toLongOrNull() ?: return null
        val at = parts[3].toLongOrNull() ?: return null
        val seen = parts[4].toLongOrNull() ?: return null
        return Meta(ownerId, parts[0], bytes, parts[2] == "1", at, seen)
    }

    /** Готовый к показу ролик владельца (null — файла нет или он испорчен). */
    fun fileFor(context: Context, ownerId: String): File? {
        val meta = metaFor(context, ownerId) ?: return null
        val file = File(dir(context), meta.sha256 + ".mp4")
        return file.takeIf { it.isFile && it.length() == meta.bytes }
    }

    fun circleFor(context: Context, ownerId: String): Boolean = metaFor(context, ownerId)?.circle ?: true

    /**
     * Объявление владельца: запомнить, чего ждать. true — файл нужен (его нет
     * или он от другого ролика), отправителю стоит запросить его готовностью.
     * Пустой sha — снятие видеоаватара: запись и файл уходят.
     */
    @Synchronized
    fun noteAnnounce(
        context: Context,
        ownerId: String,
        sha256: String,
        bytes: Long,
        circle: Boolean,
        updatedAtMs: Long,
    ): Boolean {
        if (ownerId.isBlank()) return false
        if (sha256.isEmpty()) {
            retract(context, ownerId)
            return false
        }
        val current = metaFor(context, ownerId)
        val visibleChange = current == null || current.sha256 != sha256 ||
            current.bytes != bytes || current.circle != circle
        // Сначала сохраняем объявление, затем уведомляем Compose. Иначе уже
        // открытый список запомнит «видео нет» и не покажет кольцо до установки.
        writeMeta(
            context, ownerId, sha256, bytes, circle,
            maxOf(current?.updatedAtMs ?: 0L, updatedAtMs),
            System.currentTimeMillis(),
        )
        if (visibleChange) _changed.update { it + 1 }
        return fileFor(context, ownerId) == null
    }

    /**
     * Поставить приехавший ролик. Проверяются sha256 (то, что объявил владелец)
     * и размер: подмена и обрезанный файл не доходят до показа.
     */
    fun install(context: Context, ownerId: String, source: File, expected: Meta): Boolean {
        if (!source.isFile) return false
        if (source.length() != expected.bytes) {
            Log.w(TAG, "peer video avatar size mismatch for $ownerId, refused")
            return false
        }
        val sha = sha256Of(source)
        if (sha != expected.sha256) {
            Log.w(TAG, "peer video avatar sha mismatch for $ownerId, refused")
            return false
        }
        val dir = dir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "peer video avatar dir is unavailable")
            return false
        }
        val target = File(dir, expected.sha256 + ".mp4")
        val temp = File(dir, expected.sha256 + "-" + UUID.randomUUID() + ".part")
        synchronized(this) { activeParts.add(temp.name) }
        try {
            FileInputStream(source).use { input ->
                temp.outputStream().use { out ->
                    check(copyWithLimit(input, out, com.vladimir.messenger.data.profile.VideoAvatarPolicy.MAX_EXPORT_BYTES)) {
                        "Файл видеоаватара собеседника больше предела"
                    }
                    out.fd.sync()
                }
            }
            val installed = synchronized(this) {
                // Пока копировали, владелец мог заменить или снять ролик.
                // Не восстанавливаем старое объявление поверх более нового.
                val current = metaFor(context, ownerId)
                if (current == null || current.sha256 != expected.sha256 || current.bytes != expected.bytes) {
                    false
                } else if (!temp.renameTo(target)) {
                    false
                } else {
                    _changed.update { it + 1 }
                    true
                }
            }
            if (!installed) {
                Log.w(TAG, "peer video avatar changed or could not be installed for $ownerId")
                return false
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "peer video avatar install failed for $ownerId: ${failure.message}")
            return false
        } finally {
            synchronized(this) {
                temp.delete()
                activeParts.remove(temp.name)
            }
        }
        sweep(context)
        return true
    }

    /** Видеоаватар убран владельцем: запись исчезает, остаётся постер. */
    @Synchronized
    fun retract(context: Context, ownerId: String) {
        val current = metaFor(context, ownerId) ?: return
        prefs(context).edit().remove(ownerId).apply()
        // Один и тот же ролик может быть у нескольких людей.
        if (metas(context).none { it.sha256 == current.sha256 }) {
            File(dir(context), current.sha256 + ".mp4").delete()
        }
        _changed.update { it + 1 }
    }

    /** Держать хранилище в пределах: лишнее по давности показа удаляется. */
    @Synchronized
    fun sweep(context: Context) {
        val prefs = prefs(context)
        val all = prefs.all.mapNotNull { (owner, raw) ->
            (raw as? String)?.let { parseMeta(owner, it) }
        }
        val dir = dir(context)
        val referenced = all.map { it.sha256 + ".mp4" }.toSet()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in referenced && file.name !in activeParts) file.delete()
        }
        var overCount = all.size - MAX_FILES
        var overBytes = all.sumOf { it.bytes } - MAX_TOTAL_BYTES
        if (overCount <= 0 && overBytes <= 0) return
        val editor = prefs.edit()
        val removed = mutableListOf<Meta>()
        all.sortedBy { it.lastSeenMs }.forEach { meta ->
            if (overCount <= 0 && overBytes <= 0) return@forEach
            editor.remove(meta.ownerId)
            removed += meta
            overCount -= 1
            overBytes -= meta.bytes
        }
        editor.apply()
        val stillUsed = metas(context).map { it.sha256 }.toSet()
        removed.filter { it.sha256 !in stillUsed }.forEach { File(dir, it.sha256 + ".mp4").delete() }
        _changed.update { it + 1 }
    }

    /** Ролики без файлов: кому повторить просьбу при старте (догоняющий запрос). */
    fun missing(context: Context): List<Meta> =
        prefs(context).all.mapNotNull { (owner, raw) ->
            (raw as? String)?.let { parseMeta(owner, it) }
        }.filter { meta -> File(dir(context), meta.sha256 + ".mp4").let { !it.isFile || it.length() != meta.bytes } }

    /**
     * Скачивания роликов собеседников для кольца загрузки: владелец → доля
     * полученных байтов (0..0.95 до проверки и установки). Когда файла ещё
     * нет в передаче, записи нет и отметка стоит в начале кольца.
     */
    data class Download(val sha256: String, val fraction: Float)

    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloads = _downloads.asStateFlow()

    fun setDownloads(map: Map<String, Download>) {
        if (_downloads.value != map) _downloads.value = map
    }

    /** Все объявления собеседников: для отчёта «Логов» и догоняющей просьбы. */
    fun metas(context: Context): List<Meta> =
        prefs(context).all.mapNotNull { (owner, raw) -> (raw as? String)?.let { parseMeta(owner, it) } }

    /**
     * Счётчик событий обмена (объявления, просьбы, файлы) для отчёта «Логов».
     * Живёт в отдельных настройках: [sweep] разбирает каждый ключ своих
     * настроек как метаданные и счётчики там мешали бы уборке.
     */
    @Synchronized
    fun bump(context: Context, key: String) {
        val prefs = context.getSharedPreferences(COUNTS, Context.MODE_PRIVATE)
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1L).apply()
    }

    /** Раздел «[видеоаватары]» в «Логах»: где сейчас цепочка обмена с друзьями. */
    fun report(context: Context): String {
        val all = metas(context)
        val have = all.count { fileFor(context, it.ownerId) != null }
        val going = downloads.value.size
        val counts = context.getSharedPreferences(COUNTS, Context.MODE_PRIVATE)
        fun n(key: String): Long = counts.getLong(key, 0L)
        return "[видеоаватары]\n" +
            "собеседников с видео: ${all.size} · роликов на месте: $have · ждут файл: ${all.size - have} · есть передача: $going\n" +
            "объявления: принято ${n("announce-in")}, прямых отправок ${n("announce-out")}, отложено ${n("announce-deferred")} · " +
            "просьбы: прямых отправок ${n("ready-out")}, отложено ${n("ready-deferred")}, принято ${n("ready-in")} · " +
            "файлы: получено ${n("file-in")}, отклонено ${n("file-reject")}, подготовлено ${n("file-out")}\n" +
            "Кольцо показывает принятые байты; полный круг — файл проверен, далее запуск видео. Транспортный успех не подтверждает показ.\n"
    }

    private fun parseMeta(owner: String, raw: String): Meta? {
        val parts = raw.split("|")
        if (parts.size != 5) return null
        val bytes = parts[1].toLongOrNull() ?: return null
        val at = parts[3].toLongOrNull() ?: return null
        val seen = parts[4].toLongOrNull() ?: return null
        return Meta(owner, parts[0], bytes, parts[2] == "1", at, seen)
    }

    private fun writeMeta(
        context: Context,
        ownerId: String,
        sha256: String,
        bytes: Long,
        circle: Boolean,
        updatedAtMs: Long,
        lastSeenMs: Long,
    ) {
        prefs(context).edit()
            .putString(ownerId, "$sha256|$bytes|${if (circle) 1 else 0}|$updatedAtMs|$lastSeenMs")
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(context: Context) = File(context.noBackupFilesDir, DIR)

    /**
     * Потоковое чтение с пределом: файл не читается в память целиком. null —
     * файл больше предела аватара (такой ролик к анонсу не допускается).
     */
    fun sha256Of(file: File): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > com.vladimir.messenger.data.profile.VideoAvatarPolicy.MAX_EXPORT_BYTES) {
                    return null
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
