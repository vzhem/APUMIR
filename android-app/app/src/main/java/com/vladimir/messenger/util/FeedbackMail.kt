package com.vladimir.messenger.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Письмо разработчику: текст, логи и скриншоты, которые человек выбрал сам
 * из галереи (баг или пожелание может касаться любого экрана).
 *
 * Письмо формирует почтовое приложение телефона; сама программа ничего не
 * отправляет в сеть. Вложения лежат в кэше приложения и отдаются через
 * FileProvider.
 */
object FeedbackMail {
    /** ЗАГЛУШКА: заменить на адрес разработчика до публикации. */
    const val EMAIL = "feedback@example.com"

    /** Сколько скриншотов можно приложить к одному письму. */
    const val MAX_SCREENSHOTS = 10

    private const val DIR = "feedback"
    private const val LOGS_TIMEOUT_MS = 60_000L

    private fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /**
     * Копирует изображение, выбранное в галерее, в кэш приложения.
     * Возвращает null, если файл не удалось прочитать.
     */
    suspend fun copyScreenshot(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val ext = context.contentResolver.getType(uri)
                ?.substringAfter('/', "png")
                ?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() } }
                ?: "png"
            val file = File(dir(context), "apu-shot-${System.nanoTime()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            file
        } catch (e: Exception) {
            null
        }
    }

    /** Удаляет временные файлы вложений, когда письмо уже не нужно. */
    fun clearAttachments(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Собирает безопасный отчёт «Логи» (без переписок и ключей) в файл.
     * Возвращает null, если сбор не уложился в лимит времени.
     */
    suspend fun writeLogs(context: Context): File? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(LOGS_TIMEOUT_MS) {
            val snapshot = TransferDiagnostics.collect(context.applicationContext)
            val file = File(dir(context), "apu-logs-${System.currentTimeMillis()}.txt")
            file.writeText(snapshot.report)
            file
        }
    }

    /**
     * Открывает почтовое приложение с готовым письмом. Возвращает false, если
     * подходящего приложения на телефоне нет.
     */
    fun send(context: Context, subject: String, body: String, attachments: List<File>): Boolean {
        val uris = attachments
            .filter { it.exists() }
            .map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
        val intent = if (uris.isEmpty()) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                // ClipData нужен, чтобы почтовое приложение получило доступ к файлам.
                clipData = ClipData.newRawUri("attachment", uris.first()).also { data ->
                    uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return try {
            context.startActivity(Intent.createChooser(intent, subject).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
