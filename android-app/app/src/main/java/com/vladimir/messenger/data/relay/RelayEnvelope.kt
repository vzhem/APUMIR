package com.vladimir.messenger.data.relay

/**
 * Wire format для payload, которыми обмениваются через Cloudflare relay (и другие
 * fallback-транспорты).
 *
 * Чистый Kotlin (без org.json / Android-зависимостей), поэтому полностью
 * симметричен между build и parse и unit-тестируем в обычной JVM.
 *
 * Типы envelope:
 *  - "message": сообщение чата, адресованное получателю.
 *  - "ack":     подтверждение доставки message_id (получатель → отправитель).
 *
 * Relay/транспорт видит только этот непрозрачный JSON; содержимое сообщения
 * E2E-шифруется на более высоком уровне (см. docs/OFFLINE_DELIVERY.md, раздел 3.10).
 */
object RelayEnvelope {
    const val TYPE_MESSAGE = "message"
    const val TYPE_ACK = "ack"

    private const val KEY_TYPE = "type"
    private const val KEY_MESSAGE_ID = "messageId"
    private const val KEY_CHAT_ID = "chatId"
    private const val KEY_CONTENT = "content"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_FROM = "from"

    sealed class Parsed {
        /** Входящее сообщение чата. */
        data class Message(
            val messageId: String,
            val chatId: String,
            val content: String,
            val timestamp: Long,
        ) : Parsed()

        /** Подтверждение доставки (получатель → отправитель). */
        data class Ack(
            val messageId: String,
            val from: String,
        ) : Parsed()

        /** Что-то нераспознанное (legacy plain-text, чужой payload). */
        data class Other(val raw: String) : Parsed()
    }

    /** Собрать envelope "message". */
    fun buildMessage(
        messageId: String,
        chatId: String,
        content: String,
        timestamp: Long,
        senderId: String,
    ): String {
        val sb = StringBuilder("{")
        appendKV(sb, KEY_TYPE, TYPE_MESSAGE)
        sb.append(',')
        appendKV(sb, KEY_MESSAGE_ID, messageId)
        sb.append(',')
        appendKV(sb, KEY_CHAT_ID, chatId)
        sb.append(',')
        appendKV(sb, KEY_CONTENT, content)
        sb.append(',')
        appendKV(sb, KEY_TIMESTAMP, timestamp)
        sb.append(',')
        appendKV(sb, KEY_FROM, senderId)
        sb.append('}')
        return sb.toString()
    }

    /** Собрать envelope "ack" (получатель → отправитель). */
    fun buildAck(
        messageId: String,
        from: String,
        timestamp: Long = System.currentTimeMillis(),
    ): String {
        val sb = StringBuilder("{")
        appendKV(sb, KEY_TYPE, TYPE_ACK)
        sb.append(',')
        appendKV(sb, KEY_MESSAGE_ID, messageId)
        sb.append(',')
        appendKV(sb, KEY_FROM, from)
        sb.append(',')
        appendKV(sb, KEY_TIMESTAMP, timestamp)
        sb.append('}')
        return sb.toString()
    }

    /**
     * Разобрать входящий payload. Толерантный: повреждённый JSON → [Parsed.Other]
     * с сырым текстом (чтобы legacy plain-text сообщения всё равно дошли до вызывающего).
     */
    fun parse(payload: String): Parsed {
        if (payload.isBlank()) return Parsed.Other(payload)
        val fields = try {
            parseFlatObject(payload)
        } catch (_: Exception) {
            return Parsed.Other(payload)
        }
        return when (fields[KEY_TYPE]) {
            TYPE_ACK -> {
                val messageId = fields[KEY_MESSAGE_ID] ?: return Parsed.Other(payload)
                Parsed.Ack(messageId = messageId, from = fields[KEY_FROM].orEmpty())
            }
            TYPE_MESSAGE -> {
                val messageId = fields[KEY_MESSAGE_ID] ?: return Parsed.Other(payload)
                Parsed.Message(
                    messageId = messageId,
                    chatId = fields[KEY_CHAT_ID].orEmpty(),
                    content = fields[KEY_CONTENT].orEmpty(),
                    timestamp = fields[KEY_TIMESTAMP]?.toLongOrNull()
                        ?: System.currentTimeMillis(),
                )
            }
            else -> Parsed.Other(payload)
        }
    }

    // ── JSON helpers (только плоский объект) ─────────────────────────

    private fun appendKV(sb: StringBuilder, key: String, value: String) {
        sb.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"')
    }

    private fun appendKV(sb: StringBuilder, key: String, value: Long) {
        sb.append('"').append(escape(key)).append("\":").append(value)
    }

    private fun escape(s: String): String {
        if (s.none { it == '"' || it == '\\' || it == '\n' || it == '\r' || it == '\t' || it.code < 0x20 }) return s
        val sb = StringBuilder(s.length + 4)
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    /** Минимальный толерантный парсер плоского JSON-объекта в Map<String,String>. */
    private fun parseFlatObject(json: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val n = json.length
        var i = skipWs(json, 0)
        if (i >= n || json[i] != '{') throw IllegalArgumentException("expected '{'")
        i++ // consume '{'
        i = skipWs(json, i)
        if (i < n && json[i] == '}') return result // пустой объект
        while (i < n) {
            i = skipWs(json, i)
            if (i >= n || json[i] != '"') throw IllegalArgumentException("expected key string")
            val key = StringBuilder()
            i = readString(json, i, key)
            i = skipWs(json, i)
            if (i >= n || json[i] != ':') throw IllegalArgumentException("expected ':'")
            i++ // consume ':'
            i = skipWs(json, i)
            val value = StringBuilder()
            i = readValue(json, i, value)
            result[key.toString()] = value.toString()
            i = skipWs(json, i)
            if (i < n && json[i] == ',') { i++; continue }
            return result // '}' или конец — достаточно
        }
        return result
    }

    private fun readString(json: String, start: Int, out: StringBuilder): Int {
        var i = start + 1 // пропустить открывающую кавычку
        val n = json.length
        while (i < n) {
            val c = json[i]
            if (c == '\\') {
                i++
                if (i >= n) break
                when (val e = json[i]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        val hex = if (i + 4 < n) json.substring(i + 1, i + 5) else ""
                        hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                        i += 4
                    }
                    else -> out.append(e)
                }
                i++
            } else if (c == '"') {
                return i + 1 // закрывающая кавычка
            } else {
                out.append(c); i++
            }
        }
        return i
    }

    private fun readValue(json: String, start: Int, out: StringBuilder): Int {
        val n = json.length
        if (start >= n) return start
        if (json[start] == '"') return readString(json, start, out)
        // number / true / false / null — до разделителя
        var i = start
        while (i < n) {
            val ch = json[i]
            if (ch == ',' || ch == '}' || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') break
            out.append(ch); i++
        }
        return i
    }

    private fun skipWs(json: String, i: Int): Int {
        var k = i
        while (k < json.length) {
            val c = json[k]
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break
            k++
        }
        return k
    }
}
