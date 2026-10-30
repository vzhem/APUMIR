package com.vladimir.messenger.ui.update

import android.content.Context
import com.vladimir.messenger.R
import com.vladimir.messenger.service.UpdateChecker

/**
 * Что сказать человеку, если загрузка обновления не началась.
 *
 * Одна формулировка на все входы: окно обновления, «Настройки → Обновления» и
 * любой другой экран. `UpdateDownloadBlockedException` — не поломка, а «уже
 * качается»: у него отдельный текст, чтобы человек не искал причину в сети.
 */
object UpdateDownloadMessages {

    fun forError(context: Context, error: Throwable): String = when (error) {
        is UpdateChecker.UpdateDownloadBlockedException ->
            if (error.inFlight) {
                context.getString(R.string.upd_download_already_running)
            } else {
                context.getString(
                    R.string.upd_download_wait_tpl,
                    ((error.remainingMs + 999L) / 1000L).toInt().coerceAtLeast(1),
                )
            }
        else -> context.getString(
            R.string.toast_download_failed,
            error.message ?: context.getString(R.string.upd_download_unknown_error),
        )
    }

    /** Подтверждение после успешного старта: id −1 значит «патч собран, открыт установщик». */
    fun forStarted(context: Context, downloadId: Long): String =
        if (downloadId == -1L) context.getString(R.string.upd_patch_installed)
        else context.getString(R.string.upd_download_started)
}
