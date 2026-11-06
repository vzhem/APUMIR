package com.vladimir.messenger.data.avatavideo

import android.content.Context
import android.util.Log
import com.vladimir.messenger.util.copyWithLimit
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private const val DIR = "peer-video-avatars-v1"
    private const val MAX_FILES = 200
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

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
        if (current == null || current.sha256 != sha256) {
            // Ролик сменился: старый файл немедленно не нужен, sweep доберёт.
            writeMeta(context, ownerId, sha256, bytes, circle, updatedAtMs, System.currentTimeMillis())
            return true
        }
        // Тот же ролик: обновить отметку времени и решить по наличию файла.
        writeMeta(
            context, ownerId, sha256, bytes, circle,
            maxOf(current.updatedAtMs, updatedAtMs),
            System.currentTimeMillis(),
        )
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
        val temp = File(dir, expected.sha256 + ".part")
        try {
            FileInputStream(source).use { input ->
                temp.outputStream().use { out ->
                    check(copyWithLimit(input, out, com.vladimir.messenger.data.profile.VideoAvatarPolicy.MAX_EXPORT_BYTES)) {
                        "Файл видеоаватара собеседника больше предела"
                    }
                    out.fd.sync()
                }
            }
            if (!temp.renameTo(target)) return false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            temp.delete()
            Log.w(TAG, "peer video avatar install failed for $ownerId: ${failure.message}")
            return false
        }
        writeMeta(
            context, ownerId, expected.sha256, expected.bytes, expected.circle,
            expected.updatedAtMs, System.currentTimeMillis(),
        )
        _changed.value = _changed.value + 1
        sweep(context)
        return true
    }

    /** Видеоаватар убран владельцем: запись и файл исчезают, остаётся постер. */
    fun retract(context: Context, ownerId: String) {
        val current = metaFor(context, ownerId)
        prefs(context).edit().remove(ownerId).apply()
        if (current != null) File(dir(context), current.sha256 + ".mp4").delete()
        _changed.value = _changed.value + 1
    }

    /** Держать хранилище в пределах: лишнее по давности показа удаляется. */
    fun sweep(context: Context) {
        val prefs = prefs(context)
        val all = prefs.all.mapNotNull { (owner, raw) ->
            (raw as? String)?.let { parseMeta(owner, it) }
        }
        val dir = dir(context)
        val referenced = all.map { it.sha256 + ".mp4" }.toSet()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in referenced && !file.name.endsWith(".part")) file.delete()
        }
        var overCount = all.size - MAX_FILES
        var overBytes = all.sumOf { it.bytes } - MAX_TOTAL_BYTES
        if (overCount <= 0 && overBytes <= 0) return
        val editor = prefs.edit()
        all.sortedBy { it.lastSeenMs }.forEach { meta ->
            if (overCount <= 0 && overBytes <= 0) return@forEach
            editor.remove(meta.ownerId)
            File(dir, meta.sha256 + ".mp4").delete()
            overCount -= 1
            overBytes -= meta.bytes
        }
        editor.apply()
        _changed.value = _changed.value + 1
    }

    /** Ролики без файлов: кому повторить просьбу при старте (догоняющий запрос). */
    fun missing(context: Context): List<Meta> =
        prefs(context).all.mapNotNull { (owner, raw) ->
            (raw as? String)?.let { parseMeta(owner, it) }
        }.filter { meta -> File(dir(context), meta.sha256 + ".mp4").let { !it.isFile || it.length() != meta.bytes } }

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
