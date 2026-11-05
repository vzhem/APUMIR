package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.swarm.StoragePolicy
import com.vladimir.messenger.ui.components.ApuPremiumButton
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuSettingsItem
import com.vladimir.messenger.ui.components.ApuSettingsSectionTitle
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle

/**
 * Карточки раздела «Передача файлов», вынесенные из SettingsScreen по шву
 * «очистка и место»: экран остаётся списком, а правило безопасной уборки копий
 * обновления живёт рядом с данным, которые оно показывает.
 */
@Composable
internal fun FileTransferSettingsCards(viewModel: SettingsViewModel) {
    ApuSettingsCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ApuSettingsItem(
            icon = Icons.Default.Delete,
            title = stringResource(R.string.settings_stop_stuck),
            subtitle = stringResource(R.string.settings_stop_stuck_hint),
            onClick = viewModel::onCancelStalledTransfers,
        )
        ApuSettingsDivider()
        ApuSettingsItem(
            icon = Icons.Default.CleaningServices,
            title = stringResource(R.string.settings_clear_done),
            subtitle = stringResource(R.string.settings_clear_done_hint),
            onClick = viewModel::onPurgeCompletedTransfers,
        )
    }
    UpdateStorageCard(viewModel)
    ApuSettingsSectionTitle(stringResource(R.string.section_seeding))
    SeedingDashboardCard()
}

@Composable
private fun UpdateStorageCard(viewModel: SettingsViewModel) {
    val report by viewModel.updateStorageReport.collectAsStateWithLifecycle()
    val busy by viewModel.updateStorageBusy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshUpdateStorage() }

    ApuSettingsCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ApuSettingsItem(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.storage_update_title),
            subtitle = stringResource(R.string.storage_update_hint),
        )
        val snapshot = report
        if (snapshot == null) {
            Text(
                stringResource(R.string.settings_counting_space),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        } else {
            Text(
                stringResource(
                    R.string.storage_update_installed,
                    StoragePolicy.format(snapshot.installedApkBytes),
                    StoragePolicy.format(snapshot.extractedNativeBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
            Text(
                stringResource(
                    R.string.storage_update_copies,
                    StoragePolicy.format(snapshot.updateCopyBytes),
                    StoragePolicy.format(snapshot.encryptedUpdateBytes),
                    StoragePolicy.format(snapshot.seedPlaintextBytes),
                    StoragePolicy.format(snapshot.receivedUpdatePlaintextBytes),
                    StoragePolicy.format(snapshot.installHandoffBytes),
                    StoragePolicy.format(snapshot.publicDownloadBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
            Text(
                stringResource(R.string.storage_update_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        ApuPremiumButton(
            label = stringResource(R.string.storage_update_clean),
            icon = Icons.Default.CleaningServices,
            style = DiagnosticsActionStyle.QUIET,
            enabled = !busy,
            progress = busy,
            onClick = viewModel::onCleanUpdateStorage,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}
