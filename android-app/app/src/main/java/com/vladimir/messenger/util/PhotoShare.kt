package com.vladimir.messenger.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File

/**
 * Репост наружу одним нажатием: текст, ссылка и фотографии - одним сообщением.
 *
 * Как устроено. Чужие мессенджеры принимают подпись (EXTRA_TEXT) только к
 * ОДНОМУ файлу: Telegram при ACTION_SEND с картинкой делает из текста подпись
 * (до 1024 знаков), а при ACTION_SEND_MULTIPLE текст даже не читает; так же
 * ведёт себя WhatsApp. Владелец проверил v11.70.3 (несколько файлов - дошли
 * одни снимки) и v11.70.4 (два шага - «всё сложно»). Поэтому:
 *
 * - одно фото - уходит как есть, текст со ссылкой подписью;
 * - несколько фото - собираются в одну картинку-сетку ([PhotoCollage]), как
 *   альбом в ленте, и уходят одним файлом с той же подписью;
 * - подпись длиннее 1000 знаков укорачивается так, чтобы ссылка «Открыть в
 *   APU» осталась целой: по ней получатель увидит пост полностью, с отдельными
 *   фото. Полный текст на всякий случай лежит в буфере обмена.
 *
 * Файл живёт в cache/shared и отдаётся через FileProvider (cache-path объявлен
 * в res/xml/file_paths.xml).
 */
object PhotoShare {

    private const val DIR = "shared"

    /**
     * Предел подписи к картинке у мессенджеров: Telegram - 1024 знака, при
     * превышении подпись выбрасывается целиком. Берём с запасом.
     */
    const val MAX_CAPTION_CHARS = 1000

    /** Как подпись сокращается: многоточие после обрезанного текста, перед ссылкой. */
    private const val ELLIPSIS = "…"

    /** Текст репоста: заголовок, тело и ссылка «Открыть в APU». */
    fun buildText(title: String, body: String, link: String?): String = buildString {
        val head = title.trim()
        val text = body.trim()
        if (head.isNotBlank()) append(head)
        if (text.isNotBlank() && text != head) {
            if (isNotEmpty()) append("\n\n")
            append(text)
        }
        if (!link.isNullOrBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("Открыть в APU:\n").append(link)
        }
    }

    /**
     * Подпись к картинке не длиннее [MAX_CAPTION_CHARS]: режется текст, а не
     * ссылка. [text] - результат [buildText]; [link] - та же ссылка, что там,
     * или null.
     */
    fun captionFor(text: String, link: String?, limit: Int = MAX_CAPTION_CHARS): String {
        if (text.length <= limit) return text
        val tail = if (link.isNullOrBlank()) "" else "\n\nОткрыть в APU:\n" + link
        val bodyEnd = if (tail.isNotEmpty() && text.endsWith(tail)) text.length - tail.length else text.length
        val room = (limit - tail.length - ELLIPSIS.length).coerceAtLeast(0)
        val body = text.substring(0, minOf(bodyEnd, room)).trimEnd()
        return body + ELLIPSIS + tail
    }

    /**
     * Подготовить один файл для другого приложения: одно фото - как есть,
     * несколько - сетка [PhotoCollage]. Вызывать вне главного потока. Битые
     * строки base64 пропускаются; если ни одна не разобралась - null.
     * [stem] - основа имени файла; для одного поста имя повторяется, и старый
     * файл перезаписывается.
     */
    fun writeShareImage(context: Context, images: List<String>, stem: String): Uri? {
        val decoded = images.mapNotNull { b64 -> decode(b64)?.let { b64 to it } }
        if (decoded.isEmpty()) return null
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val safeStem = stem.filter { it.isLetterOrDigit() }.take(24).ifBlank { "photo" }
        val file = File(dir, "apu-$safeStem.jpg")
        if (decoded.size == 1) {
            // Одно фото - исходные байты без перекодирования.
            val (b64, bitmap) = decoded[0]
            val raw = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            if (raw != null && raw.isNotEmpty()) file.writeBytes(raw) else writeJpeg(bitmap, file)
        } else {
            writeJpeg(PhotoCollage.compose(decoded.map { it.second }), file)
        }
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    /**
     * Намерение «картинка с подписью»: ACTION_SEND, image/jpeg, EXTRA_TEXT.
     *
     * Адрес продублирован в ClipData: по нему Android выдаёт выбранному
     * приложению право читать файл. EXTRA_SUBJECT не ставим: Telegram при
     * наличии темы склеивает её с текстом, если текст - голая ссылка.
     */
    fun buildImageIntent(uri: Uri, caption: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (caption.isNotBlank()) putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri("Фото", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** Намерение с одним текстом. */
    fun buildTextIntent(text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

    /** Положить текст поста в буфер обмена: если мессенджер потерял подпись - вставить одним нажатием. */
    fun copyToClipboard(context: Context, label: String, text: String) {
        if (text.isBlank()) return
        runCatching {
            val clipboard = context.applicationContext
                .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }

    /**
     * Открыть системное меню. Из контекста приложения, а не экрана, поэтому
     * нужен флаг новой задачи. Возвращает false, если меню открыть не удалось.
     */
    fun open(context: Context, intent: Intent, title: String): Boolean = try {
        context.startActivity(
            Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        false
    }

    private fun decode(b64: String): Bitmap? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
    }
}
