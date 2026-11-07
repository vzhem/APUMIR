package com.vladimir.messenger.util

import java.security.MessageDigest

/**
 * Короткий постоянный цифровой код профиля (позывной вида `482-915` / `APU-482-915`).
 *
 * Вычисляется детерминированно из канонического адреса узла (`pk_…`), не меняется
 * при смене IP/сети/никнейма и позволяет продиктовать или ввести 6 цифр вместо
 * длинной криптографической строки. Чистый JVM-код без Android SDK.
 */
object ProfileCallsign {
    private const val DOMAIN = "apu-callsign-v1:"
    private const val CODE_DIGITS = 6
    private const val MODULO = 1_000_000L
    private val NODE_ID_REGEX = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")
    private val PREFIX_CLEANUP = Regex("^(?:apu[-:\\s]*|#)", RegexOption.IGNORE_CASE)

    /** Канонический вид `NNN-NNN` (например, `482-915`) или null для невалидного адреса. */
    fun of(nodeId: String?): String? {
        val digits = digitsOf(nodeId) ?: return null
        return digits.substring(0, 3) + "-" + digits.substring(3, 6)
    }

    /** Вид с префиксом `APU-NNN-NNN` для текста приглашения. */
    fun withPrefix(nodeId: String?): String? = of(nodeId)?.let { "APU-$it" }

    /** Шесть цифр без разделителя (`482915`) или null. */
    fun digitsOf(nodeId: String?): String? {
        val canonical = nodeId?.trim()?.removeSuffix("_public")?.lowercase().orEmpty()
        if (!NODE_ID_REGEX.matches(canonical)) return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest((DOMAIN + canonical).toByteArray(Charsets.US_ASCII))
        var acc = 0L
        for (i in 0 until 8) {
            acc = (acc shl 8) or (hash[i].toLong() and 0xffL)
        }
        val positive = acc ushr 1
        val code = positive % MODULO
        return "%0${CODE_DIGITS}d".format(code)
    }

    /**
     * Разобрать поисковый ввод человека как цифровой код.
     * Принимает `482-915`, `482 915`, `482915`, `APU-482-915`, `#482915`, а также
     * частичный префикс от 2 до 6 цифр при живом вводе в строке поиска.
     * Возвращает только цифры либо null, если строка не похожа на цифровой код.
     */
    fun normalizeQuery(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.contains("://") || trimmed.startsWith("pk_", ignoreCase = true)) {
            return null
        }
        val withoutPrefix = trimmed.replace(PREFIX_CLEANUP, "").trim()
        if (withoutPrefix.isEmpty()) return null
        val digits = buildString(withoutPrefix.length) {
            for (ch in withoutPrefix) {
                when {
                    ch.isDigit() -> append(ch)
                    ch == '-' || ch == ' ' || ch == '\u00A0' -> Unit
                    else -> return null
                }
            }
        }
        if (digits.length !in 2..CODE_DIGITS) return null
        return digits
    }

    /** Полный ли это шестизначный код (`482-915` / `APU-482915` / `482915`). */
    fun isFullCodeQuery(raw: String?): Boolean = normalizeQuery(raw)?.length == CODE_DIGITS

    /** Совпадает ли узел [nodeId] с введённым цифровым запросом (точно или по префиксу цифр). */
    fun matches(nodeId: String?, rawQuery: String?): Boolean {
        val queryDigits = normalizeQuery(rawQuery) ?: return false
        val nodeDigits = digitsOf(nodeId) ?: return false
        return if (queryDigits.length == CODE_DIGITS) {
            nodeDigits == queryDigits
        } else {
            nodeDigits.startsWith(queryDigits)
        }
    }
}
