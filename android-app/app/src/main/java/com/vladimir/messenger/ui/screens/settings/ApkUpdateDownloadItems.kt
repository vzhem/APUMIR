package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.swarm.StoragePolicy
import com.vladimir.messenger.data.update.Offer
import com.vladimir.messenger.data.update.PatchOfferUi
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuSettingsItem

/**
 * Кнопки выбора источника обновления вынесены из [SettingsScreen] по шву
 * «состояние нажатия»: экран остаётся списком разделов, а здесь живёт правило
 * одного тапа — сразу показать «начинаю/началось» и убрать повторное нажатие.
 */
@Composable
internal fun OfficialUpdateDownloadItem(
    checking: Boolean,
    starting: Boolean,
    active: Boolean,
    blocked: Boolean,
    version: String?,
    onClick: () -> Unit,
) {
    val busy = checking || starting || active || blocked
    val title = when {
        starting -> stringResource(R.string.st_official_starting)
        active -> stringResource(R.string.st_official_started)
        checking -> stringResource(R.string.st_official_checking)
        version != null -> stringResource(R.string.st_official_download_version, version.removePrefix("v"))
        else -> stringResource(R.string.st_official_download)
    }
    val subtitle = when {
        starting || active -> stringResource(R.string.st_download_no_second_tap)
        blocked -> stringResource(R.string.st_update_already_downloading)
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
        onClick = if (busy) null else onClick,
        trailingContent = progress,
    )
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
