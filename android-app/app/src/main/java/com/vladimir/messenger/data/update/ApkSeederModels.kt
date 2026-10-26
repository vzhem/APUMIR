package com.vladimir.messenger.data.update

/**
 * Модели состояния раздачи обновления для экрана настроек.
 *
 * Вынесены из [ApkSeeder] (аудит 2026-10-26, п. 4.1): сервис раздачи — уже
 * полторы тысячи строк, и каждый новый экран тянул за собой весь этот файл
 * только ради пары `data class`. Здесь нет логики — только форма состояния,
 * поэтому менять их безопасно, не читая остальной сервис.
 */

/** Моя раздача: версия, файл, сколько получили. */
data class SeedUi(
    val version: String,
    val name: String,
    val sizeBytes: Long,
    val transferId: String?,
    val served: Int,
    val preparing: Boolean,
)

/** Объявление соседа (только версии новее моей). */
data class Offer(
    val nodeId: String,
    val version: String,
    val sha256: String,
    val sizeBytes: Long,
    val atMs: Long,
)

/** Приём обновления: ход по строке передачи. */
data class Download(
    val version: String,
    val receivedBytes: Long,
    val totalBytes: Long,
    val waitingForDeviceSpace: Boolean = false,
)

/** Обновление скачано: можно ставить и уже раздаём. */
data class Ready(
    val version: String,
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    val transferId: String,
    /**
     * Локальный файл, если он не из строки передачи (официальная загрузка,
     * взятая в раздел автоматически): установка по нему, раздача — по копии.
     */
    val sourcePath: String? = null,
)

/** Принятый в чат APK-файл (кандидат на раздачу). */
data class ReceivedApk(
    val transferId: String,
    val chatId: String,
    val fromNodeId: String,
    val displayName: String,
    val sizeBytes: Long,
    val atMs: Long,
    /** Версия из самого APK (или из имени файла); null — попросить у человека. */
    val versionGuess: String?,
)

/** Предложение соседа: он раздаёт патч от [fromVersion] до [toVersion]. */
data class PatchOfferUi(
    val nodeId: String,
    val fromVersion: String,
    val toVersion: String,
    val patchSha256: String,
    val apkSha256: String,
    val sizeBytes: Long,
    val atMs: Long,
)

/** Приём патча: ход по строке передачи. */
data class PatchDownload(
    val version: String,
    val receivedBytes: Long,
    val totalBytes: Long,
    val waitingForDeviceSpace: Boolean = false,
)
