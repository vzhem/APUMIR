package com.vladimir.messenger.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File

/**
 * Репост наружу: текст вместе с фотографиями в системное меню «Поделиться».
 *
 * Фотографии поста живут в базе строками base64 (см. [InlineImage]), а чужие
 * мессенджеры принимают только файлы по content://-адресу. Поэтому перед
 * репостом каждая фотография записывается в cache/shared и отдаётся через
 * FileProvider (путь cache-path объявлен в res/xml/file_paths.xml).
 *
 * Почему текст и фото уходят ДВУМЯ отправками. Telegram, WhatsApp и почти все
 * мессенджеры при получении нескольких файлов (ACTION_SEND_MULTIPLE)
 * выбрасывают подпись EXTRA_TEXT: получатель видел фотографии и ни слова, ни
 * ссылки (владелец, 2026-09-09). Поэтому сначала уходит текст со ссылкой
 * обычным text/plain, а следом - фотографии; получатель собирает пост в чате
 * из двух сообщений. На всякий случай текст ещё и лежит в буфере обмена.
 */
object PhotoShare {

    private const val DIR = "shared"

    /**
     * Записать фотографии в кэш и вернуть их адреса для другого приложения.
     *
     * Вызывать вне главного потока: разбор base64 и запись файлов. Битые
     * строки пропускаются. [stem] - основа имени файла; для одного и того же
     * поста имена повторяются, и старые файлы просто перезаписываются.
     */
    fun writeJpegs(context: Context, images: List<String>, stem: String): List<Uri> {
        if (images.isEmpty()) return emptyList()
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val safeStem = stem.filter { it.isLetterOrDigit() }.take(24).ifBlank { "photo" }
        val uris = ArrayList<Uri>(images.size)
        images.forEachIndexed { index, b64 ->
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return@forEachIndexed
            }
            if (bytes.isEmpty()) return@forEachIndexed
            val file = File(dir, "apu-$safeStem-${index + 1}.jpg")
            file.writeBytes(bytes)
            uris.add(
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file),
            )
        }
        return uris
    }

    /** Намерение с одним текстом. */
    fun buildTextIntent(text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

    /**
     * Намерение с фотографиями: одна - ACTION_SEND, несколько -
     * ACTION_SEND_MULTIPLE. Текст к ним не прикладывается (см. выше).
     *
     * Адреса продублированы в ClipData: именно по нему Android выдаёт
     * выбранному приложению право читать файлы, а EXTRA_STREAM - лишь
     * список для самого приложения.
     */
    fun buildPhotosIntent(uris: List<Uri>): Intent {
        require(uris.isNotEmpty()) { "no photos" }
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
        intent.type = "image/jpeg"
        if (uris.size > 1) {
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        } else {
            intent.putExtra(Intent.EXTRA_STREAM, uris[0])
        }
        val clip = ClipData.newRawUri("Фото", uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /**
     * Прежний общий сборщик: без фотографий - текст, с фотографиями - только
     * фотографии (подпись мессенджеры всё равно теряют). Оставлен для
     * вызовов, где нужно одно намерение.
     */
    fun buildIntent(text: String, uris: List<Uri>): Intent =
        if (uris.isEmpty()) buildTextIntent(text) else buildPhotosIntent(uris)

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
}
