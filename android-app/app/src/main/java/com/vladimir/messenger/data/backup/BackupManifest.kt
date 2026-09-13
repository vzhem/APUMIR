package com.vladimir.messenger.data.backup

import java.util.Base64

/**
 * Первая запись архива: что это за копия и совместима ли она с этим приложением.
 *
 * Текст `ключ=значение` по строке, без JSON (в unit-тестах `org.json` -
 * заглушка). Строки с произвольным содержимым (имя) - base64. Незнакомые
 * ключи пропускаются, чтобы старая сборка могла хотя бы прочитать манифест
 * новой копии и честно сказать «копия новее приложения».
 */
data class BackupManifest(
    /** Версия раскладки архива ([BackupLayout]); копию новее не восстанавливаем. */
    val format: Int = FORMAT,
    /** Версия схемы Room на момент копии: новее нашей - отказ, старее - миграции доведут. */
    val dbVersion: Int,
    val appVersionName: String,
    val appVersionCode: Int,
    val createdAtMs: Long,
    val nodeId: String,
    val displayName: String,
    val nickname: String,
    /** Полученные файлы приложены (иначе только чаты, контакты и настройки). */
    val includesReceived: Boolean,
    val receivedFiles: Int = 0,
    val receivedBytes: Long = 0L,
) {
    fun encode(): String = buildString {
        append("apu-backup-manifest\n")
        append("format=").append(format).append('\n')
        append("db_version=").append(dbVersion).append('\n')
        append("app_version=").append(appVersionName.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }).append('\n')
        append("app_version_code=").append(appVersionCode).append('\n')
        append("created_at_ms=").append(createdAtMs).append('\n')
        append("node_id=").append(nodeId.filter { it.isLetterOrDigit() || it == '_' }).append('\n')
        append("display_name_b64=").append(b64(displayName)).append('\n')
        append("nickname=").append(nickname.filter { it.isLetterOrDigit() || it == '_' }).append('\n')
        append("includes_received=").append(if (includesReceived) 1 else 0).append('\n')
        append("received_files=").append(receivedFiles).append('\n')
        append("received_bytes=").append(receivedBytes).append('\n')
    }

    companion object {
        const val FORMAT = 1
        private const val MAX_CHARS = 16 * 1024

        /** @return манифест или null, если это не манифест копии APU / он испорчен. */
        fun decode(text: String): BackupManifest? {
            if (text.length > MAX_CHARS) return null
            val lines = text.split('\n')
            if (lines.firstOrNull() != "apu-backup-manifest") return null
            val map = HashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val eq = line.indexOf('=')
                if (eq <= 0) return null
                map[line.substring(0, eq)] = line.substring(eq + 1)
            }
            return try {
                BackupManifest(
                    format = map["format"]?.toInt() ?: return null,
                    dbVersion = map["db_version"]?.toInt() ?: return null,
                    appVersionName = map["app_version"] ?: "",
                    appVersionCode = map["app_version_code"]?.toInt() ?: 0,
                    createdAtMs = map["created_at_ms"]?.toLong() ?: 0L,
                    nodeId = map["node_id"] ?: "",
                    displayName = map["display_name_b64"]?.let { unb64(it) } ?: "",
                    nickname = map["nickname"] ?: "",
                    includesReceived = map["includes_received"] == "1",
                    receivedFiles = map["received_files"]?.toInt() ?: 0,
                    receivedBytes = map["received_bytes"]?.toLong() ?: 0L,
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun b64(value: String): String =
            Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun unb64(value: String): String =
            String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }
}
