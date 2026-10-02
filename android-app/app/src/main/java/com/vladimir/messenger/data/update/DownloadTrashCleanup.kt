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
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            val args = arrayOf(".trashed-%APU%")
            var deleted = 0
            var freed = 0L
            cr.query(
                uri,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
                selection,
                args,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
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
     * Раунд 265: телефон не должен превращаться в кладбище APK. Владелец
     * нашёл в «Скачанных» десяток APU-v*.apk по 40 МБ после каждого
     * обновления. Правило при каждом старте:
     *  - версия файла равна установленной — удаляем (уже установлена, мусор);
     *  - иначе держим только самую свежую, все старше — удаляем.
     * Рой-раздачу это НЕ ломает: соседям отдаётся собственный установленный
     * APK (applicationInfo.sourceDir), а не скачанный файл.
     */
    fun cleanOldApks(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cr = context.contentResolver
        runCatching {
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("APU-v%.apk")
            val ids = mutableListOf<Long>()
            val names = mutableListOf<String>()
            val dates = mutableListOf<Long>()
            cr.query(
                uri,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.DATE_MODIFIED,
                ),
                selection,
                args,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    ids.add(c.getLong(0))
                    names.add(c.getString(1) ?: "")
                    dates.add(if (c.isNull(2)) 0L else c.getLong(2))
                }
            }
            if (ids.isEmpty()) return
            val installed = com.vladimir.messenger.BuildConfig.VERSION_NAME
            val order = ids.indices.sortedByDescending { dates[it] }
            var keptNewest = false
            var deleted = 0
            for (i in order) {
                val ver = names[i].removePrefix("APU-v").removeSuffix(".apk")
                val delete = when {
                    // Уже установленная версия: файл своё отработал.
                    ver == installed -> true
                    // Самая свежая из оставшихся - держим (вдруг впереди установка).
                    !keptNewest -> {
                        keptNewest = true
                        false
                    }
                    else -> true
                }
                if (delete) {
                    val rowUri = ContentUris.withAppendedId(uri, ids[i])
                    val n = runCatching { cr.delete(rowUri, null, null) }.getOrDefault(0)
                    if (n > 0) deleted++
                }
            }
            if (deleted > 0) {
                Log.i(TAG, "old APK cleanup: removed $deleted file(s)")
            }
        }.onFailure { e ->
            Log.w(TAG, "old apk cleanup failed: ${e.message}")
        }
    }
}
