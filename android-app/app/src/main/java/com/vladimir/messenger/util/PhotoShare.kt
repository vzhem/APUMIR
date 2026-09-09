package com.vladimir.messenger.util

import android.content.ClipData
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
 * FileProvider (путь cache-path объявлен в res/xml/file_paths.xml). Текст
 * идёт подписью к фотографиям (EXTRA_TEXT): Telegram, WhatsApp и почта
 * показывают его под снимками.
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

    /**
     * Намерение «Поделиться»: одна фотография - ACTION_SEND, несколько -
     * ACTION_SEND_MULTIPLE, без фотографий - обычный текст.
     *
     * Адреса продублированы в ClipData: именно по нему Android выдаёт
     * выбранному приложению право читать файлы, а EXTRA_STREAM - лишь
     * список для самого приложения.
     */
    fun buildIntent(text: String, uris: List<Uri>): Intent {
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
        if (uris.isEmpty()) {
            intent.type = "text/plain"
        } else {
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
        }
        if (text.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, text)
        return intent
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
