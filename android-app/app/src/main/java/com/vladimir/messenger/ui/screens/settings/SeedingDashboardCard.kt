package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.swarm.StoragePolicy
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuSettingsItem

@Composable
internal fun SeedingDashboardCard(
    viewModel: SeedingDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ApuSettingsCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.seed_dashboard_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.seed_dashboard_summary, state.files.size, state.activePeers),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.seed_dashboard_total_speed,
                    stringResource(R.string.seed_dashboard_rate_tpl, StoragePolicy.format(state.totalSpeedBytesPerSecond)),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.seed_dashboard_total_uploaded, StoragePolicy.format(state.totalUploadedBytes)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.files.isEmpty()) {
            Text(
                stringResource(R.string.seed_dashboard_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            )
        } else {
            state.files.forEach { file ->
                val fileStatus = stringResource(
                    if (file.activePeerIds.isNotEmpty()) R.string.seed_dashboard_file_status_active
                    else R.string.seed_dashboard_file_status_idle,
                )
                ApuSettingsDivider()
                ApuSettingsItem(
                    icon = Icons.Default.Share,
                    title = file.name,
                    subtitle = stringResource(R.string.seed_dashboard_file_subtitle, file.scope, fileStatus),
                )
                Column(
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        stringResource(R.string.seed_dashboard_file_size, StoragePolicy.format(file.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            R.string.seed_dashboard_file_speed,
                            stringResource(R.string.seed_dashboard_rate_tpl, StoragePolicy.format(file.bytesPerSecond)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.seed_dashboard_file_uploaded, StoragePolicy.format(file.uploadedBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.seed_dashboard_file_peers, file.activePeerIds.size, file.completedPeers),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (file.activePeerIds.isNotEmpty()) {
                        Text(
                            stringResource(R.string.seed_dashboard_file_peer_ids, file.activePeerIds.joinToString { "…${it.takeLast(8)}" }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.seed_dashboard_file_hash, file.sha256.take(16)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.seed_dashboard_file_id, file.transferId.take(16)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (file.expiresAtMs > 0L) {
                        Text(
                            stringResource(
                                R.string.seed_dashboard_file_expiry,
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                                    .format(Date(file.expiresAtMs)),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
