package com.vladimir.messenger.data.update

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Где лежат файлы раздачи обновления и куда кладётся копия «на установку».
 *
 * Зачем отдельный объект. `ApkUpdate` намеренно не знает про Android (его
 * гоняют JVM-тесты), а место раздачи нужно сразу в двух местах — сид
 * ([ApkSeeder]) и проверка обновлений ([com.vladimir.messenger.service.UpdateChecker]),
 * и оба обязаны смотреть в ОДИН каталог, иначе раздел «Обновления» и
 * автоматическая проверка расходятся.
 *
 * Почему `filesDir`, а не `noBackupFilesDir`. FileProvider отдаёт наружу
 * только объявленные в `res/xml/file_paths.xml` каталоги, и тега для
 * `noBackupFilesDir` в androidx нет вовсе (в `FileProvider.java` разбираются
 * только `root-path`, `files-path`, `cache-path` и пять `external-*`).
 * Поэтому единственным способом отдать установщику APK из `noBackupFilesDir`
 * был `root-path`, открывавший наружу всю файловую систему. `filesDir`
 * объявляется узко — `apk_seed/v1/`, — и наружу виден только он.
 * Резервные копии это не затрагивает: в манифесте `allowBackup="false"`.
 */
object UpdateFileRoots {

    private const val TAG = "UpdateFileRoots"

    /** Действующее место раздачи: `filesDir/apk_seed/v1`. */
    fun seed(context: Context): File = File(context.filesDir, ApkUpdate.DIR_NAME)

    /** Прежнее место раздачи: `noBackupFilesDir/apk_seed/v1` (до 2026-10-26). */
    fun legacySeed(context: Context): File = File(context.noBackupFilesDir, ApkUpdate.DIR_NAME)

    /**
     * Каталог, из которого APK уходит установщику (`cache/updates`).
     * Объявлен в `file_paths.xml`; там же лежит патч-обновление из UpdateChecker.
     */
    fun installHandoff(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * Перенести уже скачанную раздачу из `noBackupFilesDir` в `filesDir`.
     *
     * Без переноса карточка «Установить» пропала бы у тех, кто скачал
     * обновление до обновления приложения, а место на диске осталось бы занятым
     * невидимым каталогом. Ошибка переноса не смертельна: обновление можно
     * скачать заново.
     */
    fun migrateLegacySeed(context: Context) {
        val legacy = legacySeed(context)
        if (!legacy.isDirectory) return
        val target = seed(context)
        runCatching {
            target.mkdirs()
            legacy.listFiles().orEmpty().forEach { old ->
                if (!old.isFile) return@forEach
                val moved = File(target, old.name)
                if (moved.isFile) {
                    old.delete()
                } else if (!old.renameTo(moved)) {
                    runCatching { old.copyTo(moved, overwrite = true) }
                        .onSuccess { old.delete() }
                }
            }
            legacy.delete()
            Log.i(TAG, "seed root moved to filesDir")
        }.onFailure { Log.w(TAG, "seed root migration failed: ${it.message}") }
    }
}
