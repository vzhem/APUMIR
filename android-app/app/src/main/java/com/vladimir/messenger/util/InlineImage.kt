package com.vladimir.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Картинка прямо внутри текста поста или сообщения.
 *
 * Есть три служебные строки, и все они начинаются с «APUIMG»:
 *
 *  * `APUIMG1:<jpeg в base64>` - картинка целиком последней строкой сообщения
 *    (старый способ, один снимок на пост). Такой пост длиннее ~10 КБ и через
 *    публичный MQTT-брокер не проходит - работает только по Wi-Fi/QUIC.
 *  * `APUIMGS1:<n>` - в тексте поста: «к посту приложено n фотографий, они
 *    едут отдельными пакетами».
 *  * `APUIMGP1:<фото>/<кусок>/<всего>:<base64>` - один кусок одной фотографии,
 *    отдельным сообщением той же темы. Кусок не длиннее [MAX_PART_B64_CHARS],
 *    чтобы пакет уложился в потолок брокера (~10 КБ на PUBLISH с учётом
 *    двойного base64 и шифрования). Лента склеивает куски в фотографии, а в
 *    комментариях и счётчиках они не участвуют.
 *
 * Так ничего не пришлось менять ни в базе, ни в доставке: пост остаётся
 * обычным текстом, а телефон постарше просто не покажет фотографии.
 */
object InlineImage {

    const val MARKER = "APUIMG1:"

    /** «К посту приложено n фото» - строка в тексте поста. */
    const val SET_MARKER = "APUIMGS1:"

    /** Кусок фотографии, отдельным сообщением темы. */
    const val PART_MARKER = "APUIMGP1:"

    /** Предел на саму строку base64 одной фотографии. */
    const val MAX_B64_CHARS = 7000

    /**
     * Предел base64 в одном куске.
     *
     * Один PUBLISH у брокера не длиннее ~10 КБ: 3200 символов base64 после
     * конверта APUGRP1 (base64url), шифрования (base64) и обёртки relay
     * (base64) дают ≈9.5 КБ. Больше - брокер молча роняет пакет, и фото
     * никогда не доходит (так было с фрагментами файлов, см. 20d9c5b).
     */
    const val MAX_PART_B64_CHARS = 3200

    /** Сколько фотографий можно приложить к одному посту. */
    const val MAX_PHOTOS = 6

    private val SIDES = intArrayOf(720, 560, 420, 320, 240)
    private val QUALITIES = intArrayOf(60, 50, 40, 30)

    /** Один кусок фотографии: номера с единицы. */
    data class Part(val photo: Int, val index: Int, val total: Int, val b64: String)

    /** Есть ли в тексте прикреплённая картинка (старый способ, целиком). */
    fun hasImage(text: String): Boolean = text.contains("\n$MARKER") || text.startsWith(MARKER)

    /** Служебная ли это строка (любой из трёх маркеров). */
    fun isServiceLine(line: String): Boolean =
        line.startsWith(MARKER) || line.startsWith(SET_MARKER) || line.startsWith(PART_MARKER)

    /** Текст без служебных строк картинок. */
    fun stripImage(text: String): String =
        text.lineSequence().filterNot { isServiceLine(it) }.joinToString("\n").trim()

    /**
     * Заменить текст сообщения, сохранив служебные строки: при правке поста
     * фотографии остаются прежними, меняются только слова.
     */
    fun replaceText(oldContent: String, newText: String): String {
        val service = oldContent.lineSequence().filter { isServiceLine(it) }.toList()
        val body = stripImage(newText)
        val lines = ArrayList<String>(service.size + 1)
        if (body.isNotEmpty()) lines.add(body)
        lines.addAll(service)
        return lines.joinToString("\n")
    }

    /** Строка base64 картинки, вложенной целиком (старый способ), или null. */
    fun extractB64(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith(MARKER) }
            ?.removePrefix(MARKER)
            ?.takeIf { it.isNotBlank() }

    /** Приклеить картинку к тексту отдельной строкой (старый способ, целиком). */
    fun attach(text: String, dataB64: String): String {
        val body = stripImage(text)
        return if (body.isEmpty()) MARKER + dataB64 else body + "\n" + MARKER + dataB64
    }

    // ── Фотографии отдельными пакетами ───────────────────────────────────────

    /** Текст поста с пометкой «фотографий: count». При count <= 0 - просто текст. */
    fun withPhotoCount(text: String, count: Int): String {
        val body = stripImage(text)
        if (count <= 0) return body
        val line = SET_MARKER + count
        return if (body.isEmpty()) line else body + "\n" + line
    }

    /** Сколько фотографий обещано в тексте поста (0, если пометки нет). */
    fun photoCount(text: String): Int =
        text.lineSequence().firstOrNull { it.startsWith(SET_MARKER) }
            ?.removePrefix(SET_MARKER)?.trim()?.toIntOrNull()
            ?.coerceIn(0, MAX_PHOTOS) ?: 0

    /** Это сообщение - кусок фотографии, а не текст. */
    fun isPart(text: String): Boolean = text.startsWith(PART_MARKER)

    fun buildPart(photo: Int, index: Int, total: Int, b64: String): String =
        "$PART_MARKER$photo/$index/$total:$b64"

    /** Разобрать кусок; null, если строка не кусок или испорчена. */
    fun parsePart(text: String): Part? {
        if (!isPart(text)) return null
        val rest = text.substring(PART_MARKER.length)
        val colon = rest.indexOf(':')
        if (colon <= 0) return null
        val head = rest.substring(0, colon).split('/')
        if (head.size != 3) return null
        val photo = head[0].toIntOrNull() ?: return null
        val index = head[1].toIntOrNull() ?: return null
        val total = head[2].toIntOrNull() ?: return null
        val b64 = rest.substring(colon + 1)
        if (photo < 1 || index < 1 || total < 1 || index > total || b64.isEmpty()) return null
        return Part(photo, index, total, b64)
    }

    /** Нарезать одну фотографию (base64) на куски-сообщения. */
    fun splitPhoto(photo: Int, b64: String): List<String> {
        if (b64.isEmpty()) return emptyList()
        val chunks = b64.chunked(MAX_PART_B64_CHARS)
        return chunks.mapIndexed { i, chunk -> buildPart(photo, i + 1, chunks.size, chunk) }
    }

    /**
     * Склеить куски в фотографии.
     *
     * Возвращает base64 только ПОЛНЫХ фотографий по возрастанию номера:
     * недошедшие куски - фото пока не показываем, а не показываем битое.
     * Повторы одного куска (пришёл дважды) безвредны.
     */
    fun assemble(partTexts: List<String>): List<String> {
        val byPhoto = HashMap<Int, HashMap<Int, Part>>()
        for (text in partTexts) {
            val part = parsePart(text) ?: continue
            val slots = byPhoto.getOrPut(part.photo) { HashMap() }
            if (!slots.containsKey(part.index)) slots[part.index] = part
        }
        return byPhoto.keys.sorted().mapNotNull { photo ->
            val parts = byPhoto.getValue(photo)
            val total = parts.values.first().total
            if (parts.size < total) return@mapNotNull null
            val sb = StringBuilder()
            for (i in 1..total) {
                val p = parts[i] ?: return@mapNotNull null
                if (p.total != total) return@mapNotNull null
                sb.append(p.b64)
            }
            sb.toString()
        }
    }

    /**
     * Сжать выбранную из галереи картинку до строки base64.
     *
     * Вызывать только вне главного потока: чтение и перекодирование картинки -
     * работа с диском.
     */
    fun compressUri(context: Context, uri: Uri): String? = try {
        val source = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
        if (source == null) null else encodeWithinLimit(source)
    } catch (e: Exception) {
        null
    }

    private fun encodeWithinLimit(source: Bitmap): String? {
        for (side in SIDES) {
            val scaled = scaleToFit(source, side)
            for (quality in QUALITIES) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (encoded.length <= MAX_B64_CHARS) return encoded
            }
        }
        return null
    }

    private fun scaleToFit(source: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source
        val ratio = maxSide.toFloat() / longest.toFloat()
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
