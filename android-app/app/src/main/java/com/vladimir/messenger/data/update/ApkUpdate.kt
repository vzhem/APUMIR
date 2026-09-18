package com.vladimir.messenger.data.update

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Раздача обновления APU (APK) роем (docs/UPDATE_SEEDING.md).
 *
 * APK — «файл группы» в виртуальном сообществе [CHAT_ID]: общий манифест
 * с меткой `grp_apkseed` (ядро `create_group_file_manifest`), один ключ и
 * одни зашифрованные куски, любой телефон с файлом целиком — сид
 * (`GroupFileSeeder`, полосы, инвентарь — механизм K2 без изменений).
 * Здесь — то, что отличает обновление от файла группы: виртуальный id,
 * версии, проверка «это APK» и общие правила сравнения.
 *
 * Никакого Android: JVM-тесты гоняют эти функции напрямую.
 */
object ApkUpdate {

    /**
     * Виртуальное сообщество раздачи обновления: id строки передачи
     * (chatId) и источник метки манифеста. В БД групп его нет — рой
     * `routeApkOffer` узнаёт его по константе, а не по таблице.
     */
    const val CHAT_ID = "apkseed"

    /** Корень файловых папок раздачи (в `noBackupFilesDir`). */
    const val DIR_NAME = "apk_seed/v1"

    /** Память о помеченной раздаче и о предложениях соседей. */
    const val SEED_FILE = "seed.v1"
    const val OFFERS_FILE = "offers.v1"
    const val REQUEST_FILE = "request.v1"

    // ── Версии ──────────────────────────────────────────────────────────────

    /**
     * Разобрать числовую версию (`11.70.29`): 1–4 компонента, не длиннее
     * 4 знаков; null — не версия. То же строгое числовое, что
     * `GroupWire.isUpdateVersion` в пакетах роя.
     */
    fun parseVersion(value: String): List<Int>? {
        if (value.isEmpty() || value.length > 23) return null
        val parts = value.split('.')
        if (parts.size !in 1..4) return null
        return parts.map { part ->
            if (part.isEmpty() || part.length > 4 || part.any { it !in '0'..'9' }) return null
            part.toInt()
        }
    }

    /**
     * Сравнить две числовые версии: отрицательное — [candidate] ниже
     * [other], 0 — равны, положительное — выше. null — хотя бы одна не
     * разобралась (сравнивать нельзя).
     */
    fun compareVersions(candidate: String, other: String): Int? {
        val a = parseVersion(candidate) ?: return null
        val b = parseVersion(other) ?: return null
        val count = maxOf(a.size, b.size)
        for (index in 0 until count) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /** [candidate] — более новая версия, чем [other] (null-версии — нет). */
    fun isNewer(candidate: String, other: String): Boolean =
        compareVersions(candidate, other)?.let { it > 0 } ?: false

    /** Точно та же версия (для «заменить только на более новую»). */
    fun isSame(candidate: String, other: String): Boolean =
        compareVersions(candidate, other) == 0

    // ── Файл ────────────────────────────────────────────────────────────────

    /**
     * Похож ли файл на APK: это ZIP, в нём `AndroidManifest.xml` и хотя бы
     * один `classes*.dex`. Подпись и install-проверку Android сделает сам
     * при установке; здесь — только отсеять не-APK до того, как рой будет
     * зашифровывать и раздавать.
     */
    fun looksLikeApk(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching {
            ZipFile(file).use { zip ->
                var hasManifest = false
                var hasDex = false
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name == "AndroidManifest.xml") hasManifest = true
                    if (name.startsWith("classes") && name.endsWith(".dex")) hasDex = true
                    if (hasManifest && hasDex) return@use true
                }
                hasManifest && hasDex
            }
        }.getOrDefault(false)
    }

    /** SHA-256 файла в нижнем регистре (хэш для визитки и просьб). */
    fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Размер в словах для карточек. */
    fun formatSize(totalBytes: Long): String = when {
        totalBytes >= 1024L * 1024L ->
            String.format(java.util.Locale.ROOT, "%.1f МБ", totalBytes / (1024.0 * 1024.0))
        totalBytes >= 1024L ->
            String.format(java.util.Locale.ROOT, "%.1f КБ", totalBytes / 1024.0)
        else -> "$totalBytes Б"
    }
}
