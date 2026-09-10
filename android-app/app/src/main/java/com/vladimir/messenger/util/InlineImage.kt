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
 * Длинный текст (рой, этап 3) ходит теми же кусками, что и фото, под тем же
 * маркером, с номером фотографии 0:
 *
 *  * `APUIMGP1:0/0/<кусков>:<правка>` - строка в тексте сообщения: «текст
 *    продолжается ещё в n кусках правки r». Номер правки нужен, чтобы после
 *    редактирования куски старого текста не подмешались к новому.
 *  * `APUIMGP1:0/<кусок>/<всего>:<id головы>/<правка>:<текст>` - кусок текста
 *    отдельным сообщением темы, не длиннее [MAX_TEXT_PART_BYTES] байт UTF-8.
 *    Id головы - id сообщения, чей текст продолжается: в теме группы длинных
 *    сообщений может быть несколько. Один пакет с текстом на 5 000 знаков
 *    через брокер не проходит, а куски проходят.
 *
 * Номер фотографии 0 выбран нарочно: прошлые версии такой кусок прячут как
 * служебный (`isPart`), но фотографией не считают (`parsePart` отвергает
 * номер 0) - у них длинный пост показывается первым куском, без мусора в
 * комментариях и без битых картинок.
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

    /** Куски текста идут под маркером фото с номером фотографии 0. */
    const val TEXT_PART_PREFIX = PART_MARKER + "0/"

    /** Пометка «текст продолжается»: `APUIMGP1:0/0/<кусков>:<правка>` строкой в тексте. */
    const val TEXT_TAIL_PREFIX = TEXT_PART_PREFIX + "0/"

    /**
     * Предел одного куска текста в байтах UTF-8.
     *
     * Кусок фото - это 3 200 символов base64 плюс заголовок, и такой пакет
     * через брокер проходит проверенно. У куска текста заголовок длиннее
     * (id головы, номер правки - до 60 байт), поэтому самого текста берём
     * на сотню байт меньше: конверт куска текста не больше конверта куска
     * фото. Кириллица - два байта на букву, то есть около 1 500 букв на
     * кусок.
     */
    const val MAX_TEXT_PART_BYTES = 3100

    /**
     * Больше кусков текста одному сообщению не даём: голова и 8 кусков - это
     * до 27 900 байт, то есть 11 000 букв кириллицы (предел сообщения) с
     * запасом на разрез по словам. Вместе с кусками фото пост обязан
     * уложиться в 24 части манифеста (PostManifest.MAX_PARTS) - это
     * проверяет отправка.
     */
    const val MAX_TEXT_PARTS = 8

    private val SIDES = intArrayOf(720, 560, 420, 320, 240)
    private val QUALITIES = intArrayOf(60, 50, 40, 30)

    /** Один кусок фотографии: номера с единицы. */
    data class Part(val photo: Int, val index: Int, val total: Int, val b64: String)

    /** Есть ли в тексте прикреплённая картинка (старый способ, целиком). */
    fun hasImage(text: String): Boolean = text.contains("\n$MARKER") || text.startsWith(MARKER)

    /** Служебная ли это строка (любой из трёх маркеров; куски и пометка текста - под маркером кусков). */
    fun isServiceLine(line: String): Boolean =
        line.startsWith(MARKER) || line.startsWith(SET_MARKER) || line.startsWith(PART_MARKER)

    /** Служебная строка о фотографиях (не о тексте): при правке слов она сохраняется. */
    private fun isPhotoServiceLine(line: String): Boolean =
        line.startsWith(MARKER) || line.startsWith(SET_MARKER)

    /** Текст без служебных строк картинок. */
    fun stripImage(text: String): String =
        text.lineSequence().filterNot { isServiceLine(it) }.joinToString("\n").trim()

    /**
     * Слова сообщения-головы БЕЗ обрезки по краям: всё до первой служебной
     * строки. Нужно для склейки длинного текста: первый кусок может кончаться
     * пробелом или переводом строки, и [stripImage] его бы съел, склеив два
     * слова на стыке кусков.
     */
    fun headWords(content: String): String {
        val lines = content.split('\n')
        val firstService = lines.indexOfFirst { isServiceLine(it) }
        val kept = if (firstService < 0) lines else lines.subList(0, firstService)
        return kept.joinToString("\n")
    }

    /**
     * Заменить текст сообщения, сохранив служебные строки фотографий: при
     * правке поста фотографии остаются прежними, меняются только слова.
     *
     * Пометка о продолжении текста (`APUIMGP1:0/0/…`) берётся из НОВОГО текста:
     * она описывает слова, а не фото. Если новый текст помещается в одно
     * сообщение, старая пометка пропадает, и куски прежней правки больше не
     * подклеиваются.
     */
    fun replaceText(oldContent: String, newText: String): String {
        val photos = oldContent.lineSequence().filter { isPhotoServiceLine(it) }.toList()
        val tail = newText.lineSequence().firstOrNull { it.startsWith(TEXT_TAIL_PREFIX) }
        // С продолжением голову не обрезаем: её край - стык с куском.
        val body = if (tail != null) headWords(newText) else stripImage(newText)
        val lines = ArrayList<String>(photos.size + 2)
        if (body.isNotEmpty()) lines.add(body)
        if (tail != null) lines.add(tail)
        lines.addAll(photos)
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

    /** Это сообщение - кусок фотографии или кусок длинного текста, а не сообщение само по себе. */
    fun isPart(text: String): Boolean = text.startsWith(PART_MARKER)

    /** Это сообщение - кусок длинного текста (номер фото 0, но не пометка о продолжении). */
    fun isTextPart(text: String): Boolean =
        text.startsWith(TEXT_PART_PREFIX) && !text.startsWith(TEXT_TAIL_PREFIX)

    /** Ждёт ли сообщение ещё каких-то кусков (фото или текста) помимо самого себя. */
    fun expectsParts(text: String): Boolean = photoCount(text) > 0 || textTail(text) != null

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

    // ── Длинный текст кусками ────────────────────────────────────────────────

    /** Пометка «текст продолжается»: номер правки и сколько кусков ждать. */
    data class TextTail(val rev: Int, val count: Int)

    /** Один кусок текста: чей он (id головы), номер правки, номер куска (с единицы). */
    data class TextPart(val headId: String, val rev: Int, val index: Int, val total: Int, val text: String)

    /** Что собралось из головы и кусков: [complete] = все куски на месте. */
    data class AssembledText(val text: String, val complete: Boolean)

    /** Пометка о продолжении из текста сообщения или null, если текст целиком в нём. */
    fun textTail(content: String): TextTail? {
        val line = content.lineSequence().firstOrNull { it.startsWith(TEXT_TAIL_PREFIX) } ?: return null
        val cells = line.substring(TEXT_TAIL_PREFIX.length).trim().split(':')
        if (cells.size != 2) return null
        val count = cells[0].toIntOrNull() ?: return null
        val rev = cells[1].toIntOrNull() ?: return null
        if (rev < 0 || count < 1 || count > MAX_TEXT_PARTS) return null
        return TextTail(rev, count)
    }

    /** Строка-пометка «продолжение в [count] кусках правки [rev]». */
    fun textTailLine(rev: Int, count: Int): String = TEXT_TAIL_PREFIX + count + ":" + rev

    /**
     * Содержимое сообщения-головы: первый кусок текста, затем пометка о
     * продолжении (если куски есть) и пометка о фотографиях (если они есть).
     * Служебные строки - всегда после слов.
     */
    fun headContent(head: String, rev: Int, textParts: Int, photoCount: Int): String {
        val lines = ArrayList<String>(3)
        if (head.isNotEmpty()) lines.add(head)
        if (textParts > 0) lines.add(textTailLine(rev, textParts))
        if (photoCount > 0) lines.add(SET_MARKER + photoCount)
        return lines.joinToString("\n")
    }

    fun buildTextPart(headId: String, rev: Int, index: Int, total: Int, text: String): String =
        "$TEXT_PART_PREFIX$index/$total:$headId/$rev:$text"

    /** Шаблон LIKE для всех кусков текста сообщения [headId] (любой правки). */
    fun textPartPattern(headId: String): String = "$TEXT_PART_PREFIX%:$headId/%"

    /**
     * Номер правки для кусков текста: секунды часов автора. Правки одного
     * сообщения так не спутать (между ними больше секунды), число помещается
     * в Int, а хранить счётчик правок нигде не нужно. У исходного сообщения
     * номер 0.
     */
    fun revisionAt(nowMs: Long): Int = ((nowMs / 1000L) % 1_000_000_000L).toInt().coerceAtLeast(1)

    /** Разобрать кусок текста; null, если это не кусок или он испорчен. */
    fun parseTextPart(text: String): TextPart? {
        if (!isTextPart(text)) return null
        val rest = text.substring(TEXT_PART_PREFIX.length)
        val colon = rest.indexOf(':')
        if (colon <= 0) return null
        val numbers = rest.substring(0, colon).split('/')
        if (numbers.size != 2) return null
        val index = numbers[0].toIntOrNull() ?: return null
        val total = numbers[1].toIntOrNull() ?: return null
        val tail = rest.substring(colon + 1)
        val colon2 = tail.indexOf(':')
        if (colon2 <= 0) return null
        val owner = tail.substring(0, colon2).split('/')
        if (owner.size != 2) return null
        val headId = owner[0]
        val rev = owner[1].toIntOrNull() ?: return null
        val body = tail.substring(colon2 + 1)
        if (headId.isBlank() || rev < 0 || index < 1 || total < 1 || index > total || body.isEmpty()) return null
        return TextPart(headId, rev, index, total, body)
    }

    /**
     * Нарезать текст на куски не длиннее [maxBytes] байт UTF-8.
     *
     * Первый кусок остаётся в самом сообщении (голова), остальные едут
     * отдельными пакетами. Режем по границам символов (суррогатные пары не
     * рвём), а когда можно - перед пробелом или переводом строки в последней
     * десятой куска, так что кусок обычно не кончается пробелом и не рвёт
     * слово: голову получатель показывает без служебных строк и обрезает по
     * краям, и пробел на стыке иначе бы пропал. Склейка кусков подряд
     * возвращает исходный текст. Пустой текст даёт один пустой кусок.
     */
    fun splitText(body: String, maxBytes: Int = MAX_TEXT_PART_BYTES): List<String> {
        if (body.isEmpty()) return listOf("")
        val budget = maxBytes.coerceAtLeast(4)
        val out = ArrayList<String>()
        var start = 0
        while (start < body.length) {
            var bytes = 0
            var i = start
            // Начало последнего пробельного участка в пределах бюджета.
            var lastBreak = -1
            while (i < body.length) {
                val cp = body.codePointAt(i)
                val width = utf8Bytes(cp)
                if (bytes + width > budget) break
                if (Character.isWhitespace(cp) && (i == start || !Character.isWhitespace(body.codePointBefore(i)))) {
                    lastBreak = i
                }
                bytes += width
                i += Character.charCount(cp)
            }
            if (i >= body.length) {
                out.add(body.substring(start))
                break
            }
            val cut = if (lastBreak > start + (i - start) * 9 / 10) lastBreak else i
            out.add(body.substring(start, cut))
            start = cut
        }
        return out
    }

    private fun utf8Bytes(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    /**
     * Собрать текст сообщения [headId] из его содержимого [headContent] и
     * кусков темы [partTexts] (куски фото и чужие куски среди них безвредны).
     * Без пометки о продолжении - просто текст без служебных строк. С
     * пометкой - голова плюс куски нужной правки по порядку; пока какого-то
     * куска нет, текст обрывается многоточием и [AssembledText.complete] =
     * false.
     */
    fun fullText(headId: String, headContent: String, partTexts: List<String>): AssembledText {
        val tail = textTail(headContent) ?: return AssembledText(stripImage(headContent), true)
        // Голова без обрезки: её край - стык с первым куском.
        val head = headWords(headContent)
        val pieces = HashMap<Int, String>()
        for (text in partTexts) {
            val part = parseTextPart(text) ?: continue
            if (part.headId != headId || part.rev != tail.rev || part.total != tail.count) continue
            if (!pieces.containsKey(part.index)) pieces[part.index] = part.text
        }
        val sb = StringBuilder(head)
        for (i in 1..tail.count) {
            val piece = pieces[i] ?: return AssembledText(sb.toString().trim() + "…", false)
            sb.append(piece)
        }
        return AssembledText(sb.toString().trim(), true)
    }

    /** Все ли куски текста сообщения на месте (без пометки - да). */
    fun textComplete(headId: String, headContent: String, partTexts: List<String>): Boolean =
        fullText(headId, headContent, partTexts).complete

    /**
     * Содержимое сообщения с подклеенными кусками текста: слова целиком,
     * затем служебные строки фотографий; пометка о продолжении снята. Без
     * пометки содержимое возвращается как есть. Нужно экранам, которые
     * показывают сообщение по его содержимому (лента темы, избранное).
     */
    fun expandContent(headId: String, headContent: String, partTexts: List<String>): String {
        if (textTail(headContent) == null) return headContent
        val assembled = fullText(headId, headContent, partTexts).text
        val photos = headContent.lineSequence().filter { isPhotoServiceLine(it) }.toList()
        val lines = ArrayList<String>(photos.size + 1)
        if (assembled.isNotEmpty()) lines.add(assembled)
        lines.addAll(photos)
        return lines.joinToString("\n")
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
