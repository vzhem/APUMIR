package com.vladimir.messenger.data.backup

import java.util.Base64

/**
 * Текстовая форма одного файла SharedPreferences для резервной копии.
 *
 * Первая строка - заголовок [HEADER], дальше по записи на строку:
 * `тип ключ значение…`, где ключ и все строковые значения - base64 (без
 * переносов), чтобы пробелы, переводы строк и любые знаки в них не ломали
 * разбор. Типы - ровно те, что умеет SharedPreferences: `S` строка, `B`
 * булево (1/0), `I` int, `L` long, `F` float (через `toString`, обратим точно),
 * `T` множество строк (значения через пробел, может быть пустым).
 *
 * Чистая JVM без Android-классов: сам файл настроек читает и пишет
 * ProfileBackup/ProfileRestore, а здесь только кодирование, которое проверяют
 * unit-тесты. Испорченный текст даёт null целиком - половину настроек
 * восстанавливать хуже, чем никакие.
 */
object PrefsCodec {
    const val HEADER = "apu-prefs-v1"
    private const val MAX_LINES = 500_000

    fun encode(entries: Map<String, *>): String {
        val sb = StringBuilder(HEADER).append('\n')
        for ((key, value) in entries) {
            val line = when (value) {
                null -> continue
                is String -> "S ${b64(key)} ${b64(value)}"
                is Boolean -> "B ${b64(key)} ${if (value) 1 else 0}"
                is Int -> "I ${b64(key)} $value"
                is Long -> "L ${b64(key)} $value"
                is Float -> "F ${b64(key)} $value"
                is Set<*> -> "T ${b64(key)}" +
                    value.filterIsInstance<String>().joinToString("") { " " + b64(it) }
                else -> continue
            }
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /** @return записи или null, если текст не наш или испорчен. */
    fun decode(text: String): Map<String, Any>? {
        val lines = text.split('\n')
        if (lines.isEmpty() || lines[0] != HEADER || lines.size > MAX_LINES) return null
        val out = LinkedHashMap<String, Any>()
        try {
            for (index in 1 until lines.size) {
                val line = lines[index]
                if (line.isEmpty()) continue
                val parts = line.split(' ')
                if (parts.size < 2) return null
                val key = unb64(parts[1])
                val value: Any = when (parts[0]) {
                    "S" -> if (parts.size == 3) unb64(parts[2]) else return null
                    "B" -> when {
                        parts.size != 3 -> return null
                        parts[2] == "1" -> true
                        parts[2] == "0" -> false
                        else -> return null
                    }
                    "I" -> if (parts.size == 3) parts[2].toInt() else return null
                    "L" -> if (parts.size == 3) parts[2].toLong() else return null
                    "F" -> if (parts.size == 3) parts[2].toFloat() else return null
                    "T" -> parts.drop(2).map { unb64(it) }.toSet()
                    else -> return null
                }
                out[key] = value
            }
        } catch (_: IllegalArgumentException) {
            // base64 или число не разобрались
            return null
        }
        return out
    }

    private fun b64(value: String): String =
        Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unb64(value: String): String =
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
}
