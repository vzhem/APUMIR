package com.vladimir.messenger.data.update

// =============================================================================
// DOWNLOADTRASHCLEANUP.KT — раунд 251: уборка мусора обновлений в «Скачанных»
// =============================================================================
// Владелец нашёл в «Скачанных» десятки файлов `.trashed-…-APU-v1….apk` по
// ~40 МБ. Это старые APK обновлений: DownloadManager качает каждый релиз в
// общую папку, а когда загрузка удаляется/заменяется, Android НЕ стирает
// файл, а переименовывает его в `.trashed-<время>-<имя>` — и они копятся.
// На старте приложения удаляем СВОЙ мусор: записи MediaStore.Downloads, чьё
// имя начинается с `.trashed-` и содержит APU. Удаляются только файлы,
// владельцем которых являемся мы (система иначе бросит исключение), поэтому
// чужие файлы телефона не трогаем. Целые APU-v….apk НЕ удаляем: они кормят
// рой-раздачу обновлений соседям (docs/UPDATE_SEEDING.md).
// =============================================================================

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log

object DownloadTrashCleanup {
    private const val TAG = "DownloadTrashCleanup"

    fun clean(context: Context) {
        // MediaStore.Downloads появился в Android 10; ниже просто не трогаем.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cr = context.contentResolver
        runCatching {
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Downloads.DATE_MODIFIED}<?"
            val args = arrayOf(".trashed-%APU%", context.packageName, ((System.currentTimeMillis() - 24L * 60 * 60 * 1000) / 1000).toString())
            var deleted = 0
            var freed = 0L
            cr.query(
                uri,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
                selection,
                args,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { c ->
                var scanned = 0
                while (scanned++ < 128 && c.moveToNext()) {
                    val id = c.getLong(0)
                    val size = if (c.isNull(1)) 0L else c.getLong(1)
                    val rowUri = ContentUris.withAppendedId(uri, id)
                    val n = runCatching { cr.delete(rowUri, null, null) }.getOrDefault(0)
                    if (n > 0) {
                        deleted++
                        freed += size
                    }
                }
            }
            if (deleted > 0) {
                Log.i(TAG, "cleaned $deleted trashed APU downloads, freed ~${freed / 1024 / 1024} MB")
            }
        }.onFailure { e ->
            // Чистка - вежливость, а не функция: любые сбои молча пропускаем.
            Log.w(TAG, "trash cleanup failed: ${e.message}")
        }
    }

    /**
     * Телефон не должен превращаться в кладбище APK. Правило при каждом
     * старте (без суточного ожидания — владелец тестирует релизы пачками):
     *  - версия файла не новее установленной — удаляем: своё отработала;
     *  - новее установленной — держим только самый свежий файл (вдруг впереди
     *    установка), остальные дубли и старые — удаляем;
     *  - файлы младше десяти минут не трогаем: загрузка может ещё идти.
     * Чужие по владельцу загрузки система удалить не даст — попытка просто
     * молча не сработает, свои (скачанные самим приложением) уберутся.
     * Рой-раздачу это НЕ ломает: соседям отдаётся установленный APK
     * (applicationInfo.sourceDir), а не скачанный файл.
     */
    fun cleanOldApks(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cr = context.contentResolver
        runCatching {
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            data class Row(val id: Long, val name: String, val modifiedSec: Long)
            val rows = mutableListOf<Row>()
            cr.query(
                uri,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.DATE_MODIFIED,
                ),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("APU-v%.apk"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (rows.size < 128 && c.moveToNext()) {
                    rows.add(
                        Row(
                            c.getLong(0),
                            c.getString(1) ?: "",
                            if (c.isNull(2)) 0L else c.getLong(2),
                        ),
                    )
                }
            }
            if (rows.isEmpty()) return
            val installed = com.vladimir.messenger.BuildConfig.VERSION_NAME.removePrefix("v")
            val versionRegex = Regex("""APU-v([0-9]+(?:\.[0-9]+)*)(?: \(\d+\))?\.apk""")
            fun versionOf(name: String): String? = versionRegex.matchEntire(name)?.groupValues?.get(1)
            fun compareVersions(a: String, b: String): Int {
                val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
                val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
                for (i in 0 until maxOf(pa.size, pb.size)) {
                    val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
                    if (diff != 0) return diff
                }
                return 0
            }
            val nowSec = System.currentTimeMillis() / 1000
            // Самый свежий файл новее установленной версии — единственный,
            // кому разрешено остаться (возможная предстоящая установка).
            val keepId = rows
                .filter { row ->
                    val v = versionOf(row.name)
                    v != null && compareVersions(v, installed) > 0
                }
                .maxByOrNull { it.modifiedSec }
                ?.id
            var deleted = 0
            for (row in rows) {
                if (row.id == keepId) continue
                // Свежая загрузка могла ещё идти: младше десяти минут не трогаем.
                if (nowSec - row.modifiedSec < 10 * 60) continue
                val rowUri = ContentUris.withAppendedId(uri, row.id)
                val n = runCatching { cr.delete(rowUri, null, null) }.getOrDefault(0)
                if (n > 0) deleted++
            }
            if (deleted > 0) {
                Log.i(TAG, "old APK cleanup: removed $deleted file(s), kept ${if (keepId != null) 1 else 0}")
            }
        }.onFailure { e ->
            Log.w(TAG, "old apk cleanup failed: ${e.message}")
        }
    }

}
