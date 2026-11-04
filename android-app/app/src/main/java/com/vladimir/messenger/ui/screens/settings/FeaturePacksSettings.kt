package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.featurepacks.FeaturePack
import com.vladimir.messenger.data.featurepacks.FeaturePackCatalog
import com.vladimir.messenger.data.featurepacks.FeaturePackStore
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor

/**
 * Раздел «Дополнительные функции»: что докачать к лёгкой базе.
 *
 * Полноценное приложение ставится маленьким и сразу работает (сообщения,
 * сообщества, приём файлов), а тяжёлые нативные части — перевод, голос
 * в текст, видеоэффекты — владелец включает здесь переключателями.
 * Ранги в этом разделе не участвуют: докачать можно в любой момент
 * (решение владельца, docs/FEATURE_PACKS.md).
 *
 * Стадия 1: каталог и выбор. Пакеты ещё не опубликованы на релизе,
 * поэтому каждая карточка честно говорит «пакет ещё не опубликован»,
 * а выбор сохраняется и подхватится загрузчиком следующей стадии.
 */
@Composable
fun FeaturePacksSettings() {
    val context = LocalContext.current
    val abi = remember { FeaturePackStore.deviceAbi() }
    var selected by remember { mutableStateOf(FeaturePackStore.selectedIds(context)) }
    val totalBytes = FeaturePackCatalog.totalBytes(selected, abi)

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
            FeaturePackCatalog.packs.forEach { pack ->
                FeaturePackRow(
                    pack = pack,
                    abi = abi,
                    checked = pack.id in selected,
                    onCheckedChange = { value ->
                        FeaturePackStore.setSelected(context, pack.id, value)
                        selected = FeaturePackStore.selectedIds(context)
                    },
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
                    onClick = {
                        FeaturePackStore.selectAll(context)
                        selected = FeaturePackStore.selectedIds(context)
                    },
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
}

@Composable
private fun FeaturePackRow(
    pack: FeaturePack,
    abi: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
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
