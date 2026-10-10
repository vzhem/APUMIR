package com.vladimir.messenger.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.core.content.FileProvider
import com.vladimir.messenger.BuildConfig
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Письмо разработчику: текст, логи и скриншот экрана, с которого открыт раздел.
 *
 * Письмо формирует почтовое приложение телефона; сама программа ничего не
 * отправляет в сеть. Вложения лежат в кэше приложения и отдаются через
 * FileProvider.
 */
object FeedbackMail {
    /** ЗАГЛУШКА: заменить на адрес разработчика до публикации. */
    const val EMAIL = "feedback@example.com"

    private const val DIR = "feedback"
    private const val LOGS_TIMEOUT_MS = 60_000L

    /** Скриншот, снятый в момент нажатия «Написать разработчику». */
    @Volatile
    var pendingScreenshot: File? = null

    private fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /**
     * Снимает текущий экран окна. Вызывается до перехода на экран обратной
     * связи, поэтому на снимке тот экран, с которого человек открыл раздел.
     * При ошибке (например, защищённый от съёмки экран) [onDone] всё равно
     * вызывается, а вложение просто не добавляется.
     */
    fun captureScreen(context: Context, onDone: () -> Unit) {
        pendingScreenshot = null
        val activity = findActivity(context)
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onDone()
            return
        }
        val window = activity.window
        val view = window.decorView
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) {
            onDone()
            return
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                window,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        pendingScreenshot = saveScreenshot(activity, bitmap)
                    }
                    bitmap.recycle()
                    onDone()
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: IllegalArgumentException) {
            bitmap.recycle()
            onDone()
        }
    }

    private fun saveScreenshot(context: Context, bitmap: Bitmap): File? = try {
        // Старые снимки удаляем: в кэше должен быть один последний.
        dir(context).listFiles()?.forEach { it.delete() }
        val file = File(dir(context), "apu-screen-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        file
    } catch (e: Exception) {
        null
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
