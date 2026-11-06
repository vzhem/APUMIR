package com.vladimir.messenger.data.avatavideo

/**
 * Провод «видеоаватары собеседников»: анонс и просьба отдать ролик.
 *
 * Зачем свой конверт, а не продолжение пакета аватара. Сжатый JPEG аватара
 * едет старым пакетом `APUGRP1|...` с ФИКСИРОВАННЫМ числом полей: клиенты
 * v11.74.487 и раньше роняют конверт с лишними полями и теряют обновление
 * вообще. Поэтому видеоаватар живёт отдельным видом конверта с тем же
 * групповым префиксом `APUGRP1|`: старые клиенты, у которых разбор вида `avv1`
 * неизвестен, глотают его как неразобранный групповой пакет («битый конверт
 * намеренно поглощается») и не показывают служебный текст в чате. Двусторонним
 * он становится только на новых клиентах: получатель отвечает готовностью
 * ([ready]), владелец отправляет ролик обычной файловой передачей.
 *
 * Форматы (поля через `|`, в поле не может быть `|` и управляющих):
 *   анонс: `APUGRP1|avv1|an|<владелец>|<sha256|пусто=снять>|<байт>|<круг 0/1>|<когда>`
 *   готов: `APUGRP1|avv1|rd|<владелец>|<sha256>` — «владелец» здесь ЧЕЙ ролик
 *       просят (адресат просьбы), а не кто просит.
 *
 * Пустой sha в анонсе — «видеоаватар убран»: получатель сносит и файл, и запись.
 */
object AvatarVideoWire {
    const val PREFIX = "APUGRP1|avv1|"
    const val MAX_WIRE_CHARS = 2048

    /** Имя файла на передаче: виден получателю и хранителю; отдельное пространство имён. */
    const val FILE_PREFIX = "apu-avatar-video-"

    sealed interface Packet {
        /** Владелец объявляет (или снимает) свой видеоаватар. */
        data class Announce(
            val ownerId: String,
            /** hex-sha256 ролика; пусто — видеоаватар убран. */
            val sha256: String,
            val bytes: Long,
            val circle: Boolean,
            val updatedAtMs: Long,
        ) : Packet

        /**
         * Контакт просит ролик: присылать файл с этим sha. `ownerId` — чей
         * ролик просят (владелец, которому адресована просьба).
         */
        data class Ready(val ownerId: String, val sha256: String) : Packet
    }

    fun isPacket(text: String?): Boolean =
        text != null && text.length <= MAX_WIRE_CHARS && text.startsWith(PREFIX)

    fun announce(ownerId: String, sha256: String, bytes: Long, circle: Boolean, updatedAtMs: Long): String =
        PREFIX + listOf(
            "an",
            ownerId,
            sha256.lowercase(),
            bytes.toString(),
            if (circle) "1" else "0",
            updatedAtMs.toString(),
        ).joinToString("|")

    /** Просьба отдать ролик: [ownerId] — чей ролик просят (получатель просьбы). */
    fun ready(ownerId: String, sha256: String): String =
        PREFIX + listOf("rd", ownerId, sha256.lowercase()).joinToString("|")

    fun parse(text: String?): Packet? {
        if (!isPacket(text)) return null
        val body = text!!.removePrefix(PREFIX)
        val parts = body.split("|")
        return when (parts[0]) {
            "an" -> if (parts.size == 6) {
                val ownerId = parts[1].trim()
                val sha = parts[2].trim().lowercase()
                val bytes = parts[3].toLongOrNull() ?: return null
                val circle = when (parts[4]) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
                val at = parts[5].toLongOrNull() ?: return null
                if (!safeField(ownerId)) return null
                if (sha.isNotEmpty() && !isSha256(sha)) return null
                if (bytes < 0L || (sha.isNotEmpty() && bytes == 0L)) return null
                if (bytes > com.vladimir.messenger.data.profile.VideoAvatarPolicy.MAX_EXPORT_BYTES) return null
                if (at < 0L) return null
                Packet.Announce(ownerId, sha, bytes, circle, at)
            } else {
                null
            }

            "rd" -> if (parts.size == 3) {
                val ownerId = parts[1].trim()
                val sha = parts[2].trim().lowercase()
                if (!safeField(ownerId) || !isSha256(sha)) return null
                Packet.Ready(ownerId, sha)
            } else {
                null
            }

            else -> null
        }
    }

    /** Имя файла передачи для ролика с этим sha. */
    fun fileName(sha256: String): String = FILE_PREFIX + sha256.take(16) + ".mp4"

    /** Служебное имя файла: не должно попадать в чат как обычное вложение. */
    fun reserved(displayName: String): Boolean = displayName.startsWith(FILE_PREFIX)

    private fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    /** Идентификаторы узлов и sha — печатные ASCII без разделителей полей. */
    private fun safeField(value: String): Boolean =
        value.isNotEmpty() && value.length <= 128 &&
            value.all { it.code in 0x21..0x7e && it != '|' }
}
