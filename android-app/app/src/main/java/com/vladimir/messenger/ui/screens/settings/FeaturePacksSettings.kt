package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vladimir.messenger.R
import com.vladimir.messenger.data.featurepacks.FeaturePack
import com.vladimir.messenger.data.featurepacks.FeaturePackCatalog
import com.vladimir.messenger.data.featurepacks.FeaturePackSeeder
import com.vladimir.messenger.data.featurepacks.FeaturePackStore
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor

/** Цвета плашек состояния пакета: видно с одного взгляда, без чтения. */
private val PackReadyColor = Color(0xFF2E7D32)
private val PackOffColor = Color(0xFF9A6B00)
private val PackBusyColor = Color(0xFF1565C0)

/**
 * Раздел «Дополнительные функции»: что докачать к лёгкой базе.
 *
 * Полноценное приложение ставится маленьким и сразу работает (сообщения,
 * сообщества, приём файлов), а тяжёлые нативные части — перевод, голос
 * в текст, видеоэффекты — владелец включает здесь переключателями.
 * Ранги в этом разделе не участвуют: докачать можно в любой момент
 * (решение владельца, docs/FEATURE_PACKS.md).
 *
 * Наглядность — главное требование владельца: у каждого пакета плашка
 * состояния и, пока качается, полоса загрузки с процентами и источником
 * (официальный сайт или соседи по рою). «Скачано — можно пользоваться»
 * видно сразу, без догадок по переключателю.
 *
 * Откуда качается пакет: в первую очередь — официальный сайт
 * apu.clan.su, без ограничений; если сайт не отвечает или перегружен —
 * сосед по рою, у которого пакет уже стоит («у кого есть, тот и
 * раздаёт»). Скачавший телефон сам становится раздающим.
 */
@Composable
fun FeaturePacksSettings(viewModel: FeaturePacksViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val abi = remember { FeaturePackStore.deviceAbi() }
    var selected by remember { mutableStateOf(FeaturePackStore.selectedIds(context)) }
    var confirmAll by remember { mutableStateOf(false) }
    val totalBytes = FeaturePackCatalog.totalBytes(selected, abi)
    val packStates by viewModel.states.collectAsState()

    // Сводка сверху: сколько функций уже скачано и включено — то есть
    // реально работает прямо сейчас. В полной версии при скачивании всё
    // встроено в APK и работает без переключателей.
    val readyCount = FeaturePackCatalog.packs.count { pack ->
        val installedPhase = packStates[pack.id]?.phase == FeaturePackSeeder.Phase.INSTALLED
        val bundled = installedPhase && FeaturePackStore.installed(context, pack.id) == null
        installedPhase && (bundled || pack.id in selected)
    }

    ApuSettingsCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.feature_packs_intro),
                style = MaterialTheme.typography.bodySmall,
                color = HintBubbleMutedColor,
            )
            Text(
                if (readyCount > 0) {
                    stringResource(
                        R.string.feature_packs_summary_ready,
                        readyCount,
                        FeaturePackCatalog.packs.size,
                    )
                } else {
                    stringResource(R.string.feature_packs_summary_none)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (readyCount > 0) PackReadyColor else HintBubbleTextColor,
            )
            FeaturePackCatalog.packs.forEach { pack ->
                FeaturePackRow(
                    pack = pack,
                    abi = abi,
                    checked = pack.id in selected,
                    state = packStates[pack.id],
                    onCheckedChange = { value ->
                        FeaturePackStore.setSelected(context, pack.id, value)
                        selected = FeaturePackStore.selectedIds(context)
                        if (value) viewModel.request(pack.id) else viewModel.cancel(pack.id)
                    },
                    onDownloadClick = { viewModel.request(pack.id) },
                    onCancelClick = { viewModel.cancel(pack.id) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.feature_packs_download_all),
                        fontWeight = FontWeight.SemiBold,
                        color = HintBubbleTextColor,
                    )
                    Text(
                        stringResource(
                            R.string.feature_packs_total,
                            formatMb(FeaturePackCatalog.totalBytesAll(abi)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = HintBubbleMutedColor,
                    )
                }
                ApuTextAction(
                    label = stringResource(R.string.feature_packs_select_all),
                    onClick = { confirmAll = true },
                )
            }
            if (selected.isNotEmpty()) {
                Text(
                    stringResource(R.string.feature_packs_total_selected, formatMb(totalBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = HintBubbleMutedColor,
                )
            }
            Text(
                stringResource(R.string.feature_packs_pending),
                style = MaterialTheme.typography.bodySmall,
                color = HintBubbleMutedColor,
            )
        }
    }

    // «Выбрать всё» не качает сразу: сначала спросим, чтобы не начать случайно.
    if (confirmAll) {
        ApuSettingsDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.feature_packs_select_all_confirm_title)) },
            text = { Text(stringResource(R.string.feature_packs_select_all_confirm_body)) },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.action_confirm),
                    danger = true,
                    onClick = {
                        FeaturePackStore.selectAll(context)
                        selected = FeaturePackStore.selectedIds(context)
                        FeaturePackCatalog.packs.forEach { viewModel.request(it.id) }
                        confirmAll = false
                    },
                )
            },
            dismissButton = {
                ApuTextAction(
                    label = stringResource(R.string.action_cancel),
                    onClick = { confirmAll = false },
                )
            },
        )
    }
}

@Composable
private fun FeaturePackRow(
    pack: FeaturePack,
    abi: String,
    checked: Boolean,
    state: FeaturePackSeeder.PackState?,
    onCheckedChange: (Boolean) -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(packTitleRes(pack.id)),
                    fontWeight = FontWeight.SemiBold,
                    color = HintBubbleTextColor,
                )
                Text(
                    stringResource(packHintRes(pack.id)),
                    style = MaterialTheme.typography.bodySmall,
                    color = HintBubbleMutedColor,
                )
                Text(
                    stringResource(R.string.feature_packs_size, formatMb(pack.sizeBytesFor(abi))),
                    style = MaterialTheme.typography.labelSmall,
                    color = HintBubbleMutedColor,
                )
            }
            ApuPremiumSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
        // Статус — на всю ширину строки: плашка, полоса загрузки, проценты.
        // Полная версия при скачивании: пакет не скачивался, а встроен в APK.
        val context = LocalContext.current
        val bundled = state?.phase == FeaturePackSeeder.Phase.INSTALLED &&
            FeaturePackStore.installed(context, pack.id) == null
        PackStatusLine(
            state = state,
            checked = checked,
            bundled = bundled,
            onDownloadClick = onDownloadClick,
            onCancelClick = onCancelClick,
        )
    }
}

/**
 * Статус пакета под его описанием — вся правда о пакете в одном месте:
 * не скачано / качается (источник, полоса, проценты, отмена) / скачано
 * и можно пользоваться / скачано, но выключено / не удалось.
 */
@Composable
private fun PackStatusLine(
    state: FeaturePackSeeder.PackState?,
    checked: Boolean,
    bundled: Boolean = false,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    val phase = state?.phase ?: FeaturePackSeeder.Phase.NOT_INSTALLED
    when (phase) {
        FeaturePackSeeder.Phase.INSTALLED -> StatusBadge(
            text = stringResource(
                when {
                    bundled -> R.string.feature_packs_status_bundled
                    checked -> R.string.feature_packs_status_ready_seeding
                    else -> R.string.feature_packs_status_downloaded_off
                },
            ),
            color = if (checked || bundled) PackReadyColor else PackOffColor,
        )
        FeaturePackSeeder.Phase.OFFICIAL_DOWNLOAD -> DownloadProgress(
            title = stringResource(R.string.feature_packs_status_site_downloading),
            state = state,
            onCancelClick = onCancelClick,
        )
        FeaturePackSeeder.Phase.DOWNLOADING -> DownloadProgress(
            title = stringResource(R.string.feature_packs_status_swarm_downloading),
            state = state,
            onCancelClick = onCancelClick,
        )
        FeaturePackSeeder.Phase.ASKING_SWARM -> Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    text = stringResource(R.string.feature_packs_status_searching),
                    color = PackBusyColor,
                    modifier = Modifier.weight(1f),
                )
                ApuTextAction(label = stringResource(R.string.feature_packs_cancel), onClick = onCancelClick)
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        FeaturePackSeeder.Phase.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(
                text = stringResource(R.string.feature_packs_status_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            ApuTextAction(label = stringResource(R.string.feature_packs_retry), onClick = onDownloadClick)
        }
        FeaturePackSeeder.Phase.NOT_INSTALLED -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(
                text = stringResource(R.string.feature_packs_status_not_downloaded),
                color = HintBubbleMutedColor,
                modifier = Modifier.weight(1f),
            )
            ApuTextAction(label = stringResource(R.string.feature_packs_download), onClick = onDownloadClick)
        }
    }
}

/** Полоса загрузки: источник, проценты, «сколько из скольки» и отмена. */
@Composable
private fun DownloadProgress(
    title: String,
    state: FeaturePackSeeder.PackState?,
    onCancelClick: () -> Unit,
) {
    val received = state?.receivedBytes ?: 0L
    val total = state?.totalBytes ?: 0L
    val percent = if (total > 0L) ((received * 100L) / total).toInt().coerceIn(0, 100) else 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(text = title, color = PackBusyColor, modifier = Modifier.weight(1f))
            ApuTextAction(label = stringResource(R.string.feature_packs_cancel), onClick = onCancelClick)
        }
        if (total > 0L) {
            LinearProgressIndicator(
                progress = percent / 100f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(
                    R.string.feature_packs_status_progress,
                    formatMb(received),
                    formatMb(total),
                    percent,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = HintBubbleMutedColor,
            )
        } else {
            // Размер сервер ещё не сказал — полоса без процентов честнее цифр.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.feature_packs_status_site_downloading),
                style = MaterialTheme.typography.labelSmall,
                color = HintBubbleMutedColor,
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private fun packTitleRes(packId: String): Int = when (packId) {
    "translate" -> R.string.feature_packs_pack_translate
    "voice-text" -> R.string.feature_packs_pack_voice
    else -> R.string.feature_packs_pack_face
}

private fun packHintRes(packId: String): Int = when (packId) {
    "translate" -> R.string.feature_packs_pack_translate_hint
    "voice-text" -> R.string.feature_packs_pack_voice_hint
    else -> R.string.feature_packs_pack_face_hint
}

private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f", bytes / 1024.0 / 1024.0)
