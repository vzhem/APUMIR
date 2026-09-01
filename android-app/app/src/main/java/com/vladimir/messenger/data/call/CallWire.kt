package com.vladimir.messenger.data.call

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Проводной формат сигнализации и голоса звонков (дизайн: docs/CALLS_BOOTSTRAP.md, раздел 8.2).
 *
 * Звонки живут поверх существующего транспорта 1:1, поэтому каждый пакет — это
 * обычный текст с префиксом APUCALL1 (не пересекается с APUGRP1 / apu-file1 /
 * apu-file-hello1 / APULAN1) и разбирается тем же приёмником в CoreServerService.
 *
 * Сигналы ездят E2E-зашифрованными внутри mesh/QUIC-текста (тот же путь, что
 * сообщения). Медиа-ключи живут ТОЛЬКО в offer/accept: голосовые кадры крышуются
 * AES-128-GCM этими ключами на любой подложке (LAN-сокет, QUIC, relay).
 *
 * Все поля, где возможен разделитель `|` или перевод строки, кодируются
 * base64url без дополнения, поэтому формат однозначен.
 *
 * Разбор строгий: любое отклонение даёт null, пакет молча отбрасывается;
 * неизвестный kind молча пропускается (старые сборки не ломаются).
 */
object CallWire {

    const val PREFIX = "APUCALL1"

    /** Сигналы крошечные; верх — из расчёта на au-кадр фолбэка (PCM 40 мс + запас). */
    const val MAX_ENVELOPE_CHARS = 12 * 1024

    const val KIND_OFFER = "offer"
    const val KIND_RING = "ring"
    const val KIND_ACCEPT = "accept"
    const val KIND_REJECT = "reject"
    const val KIND_BYE = "bye"
    const val KIND_AUDIO = "au"

    /**
     * Бандаж голосовых кадров одной строкой: звонок через брокер (любая сеть —
     * мобильная, чужой NAT, спутник) не может позволить 50 публикаций в секунду.
     * Старые сборки неизвестный kind отбросят молча (парсер ниже).
     */
    const val KIND_AUDIO_BATCH = "ab"

    /** Сколько кадров максимум упаковываем в одну ab-строку. */
    const val AUDIO_BATCH_MAX_FRAMES = 8

    /** Версия голосового канала: v1 — выделенный TCP-сокет 42109 (udp1 добавится позже). */
    const val PROTO_TCP1 = "tcp1"

    const val REJECT_DECLINE = "decline"
    const val REJECT_BUSY = "busy"

    const val BYE_END = "end"
    const val BYE_CANCEL = "cancel"
    const val BYE_TIMEOUT = "timeout"
    const val BYE_FAILED = "failed"

    /** Offer старше этого возраста = пропущенный звонок, живой звонок не зажигаем. */
    const val OFFER_FRESH_MS = 60_000L

    /** Кодеки кадров: 1 = PCM 16 кГц mono s16le (Opus = 2 — резерв под узкие пути). */
    const val CODEC_PCM_16K = 1

    const val MAX_CALLER_NAME_CHARS = 128
    const val MAX_AUDIO_PAYLOAD_BYTES = 4 * 1024

    sealed class Packet {
        /** Звонящий → принимающий: приглашение на звонок с endpoint и ключом. */
        data class Offer(
            val callId: String,
            val callerName: String,
            val tsMs: Long,
            /** IPv4 сокета звонка звонящего; null = «LAN нет, не стучись». */
            val lanHost: String?,
            val lanPort: Int,
            val proto: String,
            /** 16 байт base64url — ключ потока звонящий→принимающий. */
            val mediaKeyB64: String,
        ) : Packet()

        /** Принимающий → звонящий: телефон получил offer и трезвонит. */
        data class Ring(val callId: String) : Packet()

        /** Принимающий → звонящий: пользователь принял; endpoint и обратный ключ. */
        data class Accept(
            val callId: String,
            val lanHost: String?,
            val lanPort: Int,
            val proto: String,
            /** 16 байт base64url — ключ потока принимающий→звонящий. */
            val mediaKeyB64: String,
        ) : Packet()

        /** Принимающий → звонящий. reason: decline (отбой рукой) | busy (занято). */
        data class Reject(val callId: String, val reason: String) : Packet()

        /** Любая сторона → другая. reason: end | cancel | timeout | failed. */
        data class Bye(val callId: String, val reason: String) : Packet()

        /** Голосовой кадр по текстовому фолбэку (QUIC/relay), payload уже AES-GCM. */
        data class Audio(
            val callId: String,
            val seq: Long,
            val tsMs: Long,
            val payload: ByteArray,
        ) : Packet()

        /** Бандаж кадров для брокер-пути (любая сеть): n × (seq|ts|payload). */
        data class AudioBatch(
            val callId: String,
            val frames: List<Audio>,
        ) : Packet()
    }

    fun isCallPacket(text: String?): Boolean =
        text != null && text.length <= MAX_ENVELOPE_CHARS && text.startsWith("$PREFIX|")

    // ── Сборка ──────────────────────────────────────────────────────────────

    fun buildOffer(
        callId: String,
        callerName: String,
        tsMs: Long,
        lanHost: String?,
        lanPort: Int,
        mediaKey: ByteArray,
        proto: String = PROTO_TCP1,
    ): String {
        requireValidCallId(callId)
        require(callerName.length <= MAX_CALLER_NAME_CHARS) { "Caller name too long" }
        require(mediaKey.size == 16) { "Media key must be 16 bytes" }
        require(proto == PROTO_TCP1) { "Unknown call proto" }
        return "$PREFIX|$KIND_OFFER|$callId|${encode(callerName)}|$tsMs|${hostField(lanHost)}|${portField(lanPort)}|$proto|${encodeBytes(mediaKey)}"
    }

    fun buildRing(callId: String): String {
        requireValidCallId(callId)
        return "$PREFIX|$KIND_RING|$callId"
    }

    fun buildAccept(
        callId: String,
        lanHost: String?,
        lanPort: Int,
        mediaKey: ByteArray,
        proto: String = PROTO_TCP1,
    ): String {
        requireValidCallId(callId)
        require(mediaKey.size == 16) { "Media key must be 16 bytes" }
        return "$PREFIX|$KIND_ACCEPT|$callId|${hostField(lanHost)}|${portField(lanPort)}|$proto|${encodeBytes(mediaKey)}"
    }

    fun buildReject(callId: String, reason: String): String {
        requireValidCallId(callId)
        require(reason == REJECT_DECLINE || reason == REJECT_BUSY) { "Unknown reject reason" }
        return "$PREFIX|$KIND_REJECT|$callId|$reason"
    }

    fun buildBye(callId: String, reason: String): String {
        requireValidCallId(callId)
        require(
            reason == BYE_END || reason == BYE_CANCEL ||
                reason == BYE_TIMEOUT || reason == BYE_FAILED,
        ) { "Unknown bye reason" }
        return "$PREFIX|$KIND_BYE|$callId|$reason"
    }

    fun buildAudio(callId: String, seq: Long, tsMs: Long, payload: ByteArray): String {
        requireValidCallId(callId)
        require(seq >= 0) { "Negative audio seq" }
        require(payload.isNotEmpty() && payload.size <= MAX_AUDIO_PAYLOAD_BYTES) {
            "Audio payload out of bounds"
        }
        return "$PREFIX|$KIND_AUDIO|$callId|$seq|$tsMs|${encodeBytes(payload)}"
    }

    /** Бандаж из 1..AUDIO_BATCH_MAX_FRAMES кадров; проверки те же, что у одиночного. */
    fun buildAudioBatch(callId: String, frames: List<Packet.Audio>): String {
        requireValidCallId(callId)
        require(frames.isNotEmpty() && frames.size <= AUDIO_BATCH_MAX_FRAMES) {
            "Audio batch out of bounds"
        }
        val sb = StringBuilder("$PREFIX|$KIND_AUDIO_BATCH|$callId|${frames.size}")
        frames.forEach { f ->
            require(f.callId == callId) { "Mixed callIds in batch" }
            require(f.seq >= 0) { "Negative audio seq" }
            require(f.payload.isNotEmpty() && f.payload.size <= MAX_AUDIO_PAYLOAD_BYTES) {
                "Audio payload out of bounds"
            }
            sb.append('|').append(f.seq).append('|').append(f.tsMs).append('|')
                .append(encodeBytes(f.payload))
        }
        val s = sb.toString()
        require(s.length <= MAX_ENVELOPE_CHARS) { "Audio batch exceeds envelope" }
        return s
    }

    // ── Детерминированные messageId для транспортной дедупликации ──────────

    fun offerMessageId(callId: String, attempt: Int): String = "c${callId}o$attempt"
    fun ringMessageId(callId: String): String = "c${callId}r"
    fun acceptMessageId(callId: String, attempt: Int): String = "c${callId}a$attempt"
    fun rejectMessageId(callId: String): String = "c${callId}j"
    fun byeMessageId(callId: String, attempt: Int): String = "c${callId}b$attempt"
    fun audioMessageId(callId: String, seq: Long): String = "c${callId}au$seq"

    // ── Разбор ──────────────────────────────────────────────────────────────

    fun parse(text: String?): Packet? {
        if (!isCallPacket(text)) return null
        val parts = text!!.split('|')
        if (parts.size < 3 || parts[0] != PREFIX) return null
        return when (parts[1]) {
            KIND_OFFER -> if (parts.size == 9) {
                val callId = parts[2].takeIf { isValidCallId(it) } ?: return null
                val callerName = decode(parts[3]) ?: return null
                if (callerName.isBlank() || callerName.length > MAX_CALLER_NAME_CHARS) return null
                val tsMs = parts[4].toLongOrNull() ?: return null
                if (tsMs <= 0) return null
                val (lanHost, lanPort) = parseEndpoint(parts[5], parts[6]) ?: return null
                val proto = parts[7]
                if (proto != PROTO_TCP1) return null
                if (decodeBytes(parts[8])?.size != 16) return null
                Packet.Offer(callId, callerName, tsMs, lanHost, lanPort, proto, parts[8])
            } else {
                null
            }

            KIND_RING -> if (parts.size == 3) {
                val callId = parts[2].takeIf { isValidCallId(it) } ?: return null
                Packet.Ring(callId)
            } else {
                null
            }

            // Полей 7: префикс, вид, callId, host, port, proto, ключ.
            KIND_ACCEPT -> if (parts.size == 7) {
                val callId = parts[2].takeIf { isValidCallId(it) } ?: return null
                val (lanHost, lanPort) = parseEndpoint(parts[3], parts[4]) ?: return null
                val proto = parts[5]
                if (proto != PROTO_TCP1) return null
                if (decodeBytes(parts[6])?.size != 16) return null
                Packet.Accept(callId, lanHost, lanPort, proto, parts[6])
            } else {
                null
            }

            KIND_REJECT -> if (parts.size == 4) {
                val callId = parts[2].takeIf { isValidCallId(it) } ?: return null
                when (parts[3]) {
                    REJECT_DECLINE, REJECT_BUSY -> Packet.Reject(callId, parts[3])
                    else -> null
                }
            } else {
                null
            }

            KIND_BYE -> if (parts.size == 4) {
                val callId = parts[2].takeIf { isValidCallId(it) } ?: return null
                when (parts[3]) {
                    BYE_END, BYE_CANCEL, BYE_TIMEOUT, BYE_FAILED -> Packet.Bye(callId, parts[3])
                    else -> null
                }
            } else {
                null
            }

            KIND_AUDIO -> if (parts.size == 6) {
                val callId = parts[2].takeIf { isValidCallId(it) } ?: return null
                val seq = parts[3].toLongOrNull() ?: return null
                val tsMs = parts[4].toLongOrNull() ?: return null
                if (seq < 0 || tsMs <= 0) return null
                val payload = decodeBytes(parts[5]) ?: return null
                if (payload.isEmpty() || payload.size > MAX_AUDIO_PAYLOAD_BYTES) return null
                Packet.Audio(callId, seq, tsMs, payload)
            } else {
                null
            }

            KIND_AUDIO_BATCH -> if (parts.size >= 7) {
                val callId = parts[2].takeIf { isValidCallId(it) } ?: return null
                val n = parts[3].toIntOrNull() ?: return null
                if (n <= 0 || n > AUDIO_BATCH_MAX_FRAMES) return null
                if (parts.size != 4 + 3 * n) return null
                val frames = ArrayList<Packet.Audio>(n)
                var i = 4
                repeat(n) {
                    val seq = parts[i].toLongOrNull() ?: return null
                    val tsMs = parts[i + 1].toLongOrNull() ?: return null
                    if (seq < 0 || tsMs <= 0) return null
                    val payload = decodeBytes(parts[i + 2]) ?: return null
                    if (payload.isEmpty() || payload.size > MAX_AUDIO_PAYLOAD_BYTES) return null
                    frames += Packet.Audio(callId, seq, tsMs, payload)
                    i += 3
                }
                Packet.AudioBatch(callId, frames)
            } else {
                null
            }

            else -> null
        }
    }

    // ── Поля ────────────────────────────────────────────────────────────────

    /** Нормализованное поле хоста: "-" = нет LAN. */
    private fun hostField(lanHost: String?): String = lanHost ?: "-"

    private fun portField(lanPort: Int): String =
        if (lanPort in 1024..65535) lanPort.toString() else "-"

    /**
     * Пара host/port сигнала. ("-"+"-") = валидное «нет LAN» → Pair(null, 0).
     * null результата = битое поле, пакет надо выбросить.
     */
    private fun parseEndpoint(hostField: String, portField: String): Pair<String?, Int>? {
        if (hostField == "-") {
            return if (portField == "-") Pair(null, 0) else null
        }
        if (!hostField.matches(Regex("^[0-9.]{7,15}$"))) return null
        val port = portField.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
        return Pair(hostField, port)
    }

    fun isValidCallId(callId: String): Boolean = callId.matches(Regex("^[0-9a-f]{32}$"))

    fun requireValidCallId(callId: String) {
        require(isValidCallId(callId)) { "Invalid call ID" }
    }

    private val b64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64Decoder = Base64.getUrlDecoder()

    fun encodeBytes(bytes: ByteArray): String = b64Encoder.encodeToString(bytes)

    fun decodeBytes(value: String): ByteArray? = try {
        b64Decoder.decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun encode(value: String): String = encodeBytes(value.toByteArray(StandardCharsets.UTF_8))

    fun decode(value: String): String? = decodeBytes(value)?.let { String(it, StandardCharsets.UTF_8) }
}
