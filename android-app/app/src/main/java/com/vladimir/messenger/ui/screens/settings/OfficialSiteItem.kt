package com.vladimir.messenger.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R

/**
 * Официальный сайт одной строкой в «О приложении».
 *
 * Зачем. Владелец 2026-11-18: «в настройках нужно добавить кнопку перейти на
 * официальный сайт». Раньше адрес жил только в коде загрузчика обновлений: если
 * обновление не качается, человеку некуда пойти и он не знает, где смотреть
 * версию и файлы.
 *
 * Отдельный файл, а не строка в `SettingsScreen`: тот упёрся в потолок god-файла,
 * а кнопка — самостоятельный шов.
 */
internal const val OFFICIAL_SITE_URL = "https://apu.clan.su"

@Composable
internal fun OfficialSiteItem() {
    val context = LocalContext.current
    SettingsItem(
        icon = Icons.Default.Language,
        title = stringResource(R.string.settings_official_site),
        subtitle = stringResource(R.string.settings_official_site_hint),
        onClick = { openOfficialSite(context) },
    )
}

/**
 * Открыть сайт в браузере.
 *
 * Ошибку не глотаем молча: без браузера (бывает на урезанных прошивках) человек
 * должен увидеть, что кнопка сработала, а не решить, что она сломана. Текст
 * ошибки — без адреса: он и так подписан строкой выше.
 */
private fun openOfficialSite(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SITE_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (error: Exception) {
        Log.w("OfficialSiteItem", "site open failed: ${error.message}")
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.settings_official_site_failed),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}
