package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.swarm.StoragePolicy
import com.vladimir.messenger.data.update.Offer
import com.vladimir.messenger.data.update.PatchOfferUi
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuSettingsItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * Кнопки выбора источника обновления вынесены из [SettingsScreen] по шву
 * «состояние нажатия»: экран остаётся списком разделов, а здесь живёт правило
 * одного тапа — сразу показать «начинаю/началось» и убрать повторное нажатие.
 */
@Composable
internal fun OfficialUpdateDownloadItem(
    viewModel: SettingsViewModel,
    version: String?,
) {
    val checking by viewModel.updatesChecking.collectAsStateWithLifecycle()
    val starting by viewModel.officialDownloadStarting.collectAsStateWithLifecycle()
    val active by viewModel.officialDownloadActive.collectAsStateWithLifecycle()
    val state by viewModel.officialDownloadState.collectAsStateWithLifecycle()

    // Системное уведомление DownloadManager можно смахнуть. Пока раздел
    // «Обновления» на экране и загрузка жива, карточка сама перечитывает ход.
    // Вне экрана опроса нет; в простое телефон не будим.
    LaunchedEffect(active, state?.downloadId) {
        if (active) {
            while (true) {
                delay(2_000L)
                viewModel.refreshOfficialDownload()
            }
        }
    }

    val busy = checking || starting || active
    val title = when {
        starting -> stringResource(R.string.st_official_starting)
        active -> stringResource(R.string.st_official_started)
        checking -> stringResource(R.string.st_official_checking)
        state?.isFailed == true -> stringResource(R.string.st_official_retry_download)
        version != null -> stringResource(R.string.st_official_download_version, version.removePrefix("v"))
        else -> stringResource(R.string.st_official_download)
    }
    val subtitle = when {
        starting -> stringResource(R.string.st_download_request_accepted)
        active -> stringResource(R.string.st_download_no_second_tap)
        state?.isFailed == true -> stringResource(R.string.st_official_download_failed)
        else -> stringResource(R.string.st_official_check_download_hint)
    }
    val progress: (@Composable () -> Unit)? = if (busy) {
        { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
    } else {
        null
    }
    ApuSettingsItem(
        icon = Icons.Default.CloudDownload,
        title = title,
        subtitle = subtitle,
        onClick = if (busy) null else viewModel::onDownloadOfficialRelease,
        trailingContent = progress,
    )

    val visible = state?.takeIf { !it.isSuccessful }
    if (visible != null) {
        ApuSettingsDivider()
        val detail = when {
            visible.isFailed -> stringResource(R.string.st_official_download_failed)
            visible.status == "paused" -> stringResource(R.string.st_official_paused)
            visible.totalBytes > 0L -> stringResource(
                R.string.st_official_progress_tpl,
                StoragePolicy.format(visible.downloadedBytes),
                StoragePolicy.format(visible.totalBytes),
                visible.percent,
            )
            else -> stringResource(R.string.st_official_progress_unknown_tpl, StoragePolicy.format(visible.downloadedBytes))
        }
        ApuSettingsItem(
            icon = Icons.Default.Download,
            title = stringResource(R.string.st_official_progress_title, visible.version.removePrefix("v")),
            subtitle = detail,
        )
        if (visible.isActive) {
            ApuSettingsItem(
                icon = Icons.Default.Close,
                title = stringResource(R.string.st_official_cancel_download),
                onClick = viewModel::onCancelOfficialDownload,
            )
        }
        if (visible.isFailed) {
            ApuSettingsItem(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.st_official_retry_download),
                onClick = viewModel::onDownloadOfficialRelease,
            )
        }
    }
}

@Composable
internal fun NeighborUpdateDownloadItem(
    offers: List<Offer>,
    officialAvailable: Boolean,
    starting: Boolean,
    pendingVersion: String?,
    blocked: Boolean,
    onDownload: (String) -> Unit,
    onCancelPending: () -> Unit,
) {
    val best = offers.firstOrNull()
    val started = starting || pendingVersion != null
    if (!started && (best == null || blocked)) return

    if (officialAvailable && best != null && !started && !blocked) {
        Text(
            stringResource(R.string.settings_update_two_places),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }

    val version = pendingVersion ?: best?.version
    val title = when {
        starting -> stringResource(R.string.st_neighbor_starting)
        started -> stringResource(R.string.st_neighbor_started)
        officialAvailable && version != null -> stringResource(R.string.st_dl_net_v, version)
        version != null -> stringResource(R.string.st_dl_v, version)
        else -> stringResource(R.string.st_dl_v, "")
    }
    val subtitle = when {
        started -> stringResource(R.string.st_download_no_second_tap)
        blocked -> stringResource(R.string.st_update_already_downloading)
        offers.size > 1 && best != null ->
            stringResource(R.string.offers_many_tpl, offers.size, StoragePolicy.format(best.sizeBytes))
        best != null -> stringResource(R.string.offer_one_tpl, StoragePolicy.format(best.sizeBytes))
        else -> stringResource(R.string.st_download_no_second_tap)
    }
    val downloadClick: (() -> Unit)? = if (!started && !blocked && best != null) {
        val nodeId = best.nodeId
        { onDownload(nodeId) }
    } else {
        null
    }
    val progress: (@Composable () -> Unit)? = if (started) {
        { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
    } else {
        null
    }
    ApuSettingsItem(
        icon = Icons.Default.Download,
        title = title,
        subtitle = subtitle,
        onClick = downloadClick,
        trailingContent = progress,
    )
    if (started && pendingVersion != null) {
        ApuSettingsDivider()
        ApuSettingsItem(
            icon = Icons.Default.Close,
            title = stringResource(R.string.settings_stop_receiving),
            onClick = onCancelPending,
        )
    }
}

@Composable
internal fun PatchUpdateDownloadItem(
    offers: List<PatchOfferUi>,
    blocked: Boolean,
    onDownload: (String) -> Unit,
) {
    val best = offers.firstOrNull() ?: return
    if (blocked) return
    ApuSettingsItem(
        icon = Icons.Default.Download,
        title = stringResource(R.string.st_dl_patch_title, best.toVersion),
        subtitle = stringResource(
            R.string.patch_offer_tpl,
            StoragePolicy.format(best.sizeBytes),
            offers.size.coerceAtLeast(1),
        ),
        onClick = { onDownload(best.nodeId) },
    )
}
