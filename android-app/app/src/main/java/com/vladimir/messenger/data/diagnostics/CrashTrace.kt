package com.vladimir.messenger.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * След последнего падения процесса: трасса сохраняется приложением самим.
 *
 * Зачем отдельно от [RuntimeDiagnostics]. Та диагностика намеренно ограничена
 * метриками без стеков и данных переписки. А падение экрана (владелец
 * 2026-11-06: «Записать видео» роняло и видеоаватар, и видеоистории) после
 * смерти процесса не оставляет НИЧЕГО разбираемого: системный журнал пишет
 * `FATAL EXCEPTION` от имени уже мёртвого процесса, а отчёт «Логи» читает
 * logcat только текущего PID. Здесь два источника одного служебного файла:
 *
 *  1) обработчик непойманных исключений пишет трассу ДО смерти процесса;
 *  2) на Android 11+ система хранит трассы последних выходов процесса в
 *     `ApplicationExitInfo` — при старте она переписывается в этот файл, если
 *     его ещё нет. Это читает и падение СТАРОЙ сборки: человек обновился и
 *     сразу видит, на чём падало до исправления.
 *
 * Файл один, ограничен по размеру, в отчёте показывается секцией
 * «[последнее падение]» с той же обрезкой и редактированием строк, что у
 * строк logcat. Данные переписки в трассу не попадают: это стек кода
 * приложения.
 */
object CrashTrace {
    private const val TAG = "CrashTrace"
    private const val FILE_NAME = "last-crash-trace.txt"
    private const val MAX_BYTES = 24 * 1024
    private const val REPORT_HEADER = "[последнее падение]"
    private const val REPORT_LINES = 48

    /** Поставить рано, до первого окна: падение может случиться где угодно. */
    fun install(context: Context) {
        val app = context.applicationContext
        runCatching { captureSystemTrace(app) }
            .onFailure { Log.w(TAG, "Cannot read the system crash trace", it) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                write(
                    app,
                    "${thread.name}: ${error.javaClass.name}: ${error.message}\n" +
                        Log.getStackTraceString(error),
                )
            }.onFailure { Log.w(TAG, "Cannot write the crash trace", it) }
            // Системе отдаём падение как есть: диалог «приложение остановлено»
            // и запись в истории выходов должны остаться штатными.
            previous?.uncaughtException(thread, error)
        }
    }

    /** Секция для отчёта «Логи»; пусто, пока падений не записано. */
    fun report(context: Context): String {
        val text = runCatching {
            File(context.applicationContext.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()
        }.getOrNull()
        if (text.isNullOrBlank()) return ""
        val body = text.lineSequence()
            .map { DiagnosticsPrivacy.clipLogcatLine(DiagnosticsPrivacy.redact(it)) }
            .take(REPORT_LINES)
            .joinToString("\n")
        return REPORT_HEADER + "\n" + body + "\n"
    }

    private fun write(context: Context, text: String) {
        File(context.filesDir, FILE_NAME).writeText(text.take(MAX_BYTES))
    }

    /**
     * Трасса последнего падения из системной истории выходов (Android 11+).
     * Пишется только в пустой файл: след от обработчика новее и точнее.
     */
    private fun captureSystemTrace(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val file = File(context.filesDir, FILE_NAME)
        if (file.isFile && file.length() > 0) return
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val crash = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            .firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH } ?: return
        val trace = runCatching {
            crash.traceInputStream?.bufferedReader()?.use { reader -> reader.readText() }
        }.getOrNull()
        if (!trace.isNullOrBlank()) write(context, trace)
    }
}
