package com.vladimir.messenger.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.util.LocationShare

/**
 * Карточка «Геопозиция» в пузыре сообщения (личные чаты и группы): метка,
 * координаты, точность и подпись отправителя. Тап открывает точку в картах
 * (geo:), а если карт на телефоне нет — в браузере на OpenStreetMap.
 */
@Composable
fun LocationCardView(
    location: LocationShare.Shared,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Place,
                contentDescription = stringResource(R.string.loc_card_title),
                tint = Color(0xFFE5484D),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.loc_card_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
            )
        }
        if (location.label.isNotBlank()) {
            Text(
                location.label,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            LocationShare.coordsText(location),
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 2.dp),
        )
        location.accuracyM?.let { accuracy ->
            Text(
                stringResource(R.string.loc_accuracy, accuracy),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
            )
        }
        Text(
            stringResource(R.string.loc_open_maps),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable {
                    val geo = Intent(Intent.ACTION_VIEW, Uri.parse(LocationShare.geoUri(location)))
                    try {
                        context.startActivity(geo)
                    } catch (e: ActivityNotFoundException) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(LocationShare.webUrl(location.lat, location.lon)),
                                ),
                            )
                        }
                    }
                },
        )
    }
}

/**
 * Запрос координат по нажатию кнопки метки: разрешение спрашиваем только в
 * этот момент; возвращает (действие запуска, идёт ли определение).
 * [onReady] получает фикс, [onError] — готовый текст подсказки для плашки чата.
 */
@Composable
fun rememberShareLocationAction(
    onReady: (com.vladimir.messenger.data.location.CurrentLocation.Fix) -> Unit,
    onError: (String) -> Unit,
): Pair<() -> Unit, Boolean> {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var locating by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    fun fetch() {
        if (locating) return
        locating = true
        scope.launch {
            val fix = runCatching {
                com.vladimir.messenger.data.location.CurrentLocation.fetch(context)
            }.getOrNull()
            locating = false
            if (fix == null) {
                onError(context.getString(R.string.loc_unavailable))
            } else {
                onReady(fix)
            }
        }
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            fetch()
        } else {
            onError(context.getString(R.string.loc_permission_denied))
        }
    }

    val request: () -> Unit = {
        if (com.vladimir.messenger.data.location.CurrentLocation.hasPermission(context)) {
            fetch()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }
    return request to locating
}

/**
 * Подтверждение отправки: координаты видны до отправки (это приватные
 * данные), подпись — необязательная («Я у входа», «Дом»…).
 */
@Composable
fun LocationConfirmDialog(
    fix: com.vladimir.messenger.data.location.CurrentLocation.Fix,
    onDismiss: () -> Unit,
    onSend: (label: String) -> Unit,
) {
    var label by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.loc_dialog_title)) },
        text = {
            Column {
                Text(
                    LocationShare.coordsText(
                        LocationShare.Shared(fix.lat, fix.lon, fix.accuracyM, ""),
                    ) + (fix.accuracyM?.let { " · ±" + it + " м" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ApuBubbleTextColor,
                )
                Spacer(Modifier.size(10.dp))
                ApuFormTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = stringResource(R.string.loc_dialog_label_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.loc_send),
                onClick = { onSend(label.trim()) },
            )
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.loc_cancel), onClick = onDismiss)
        },
    )
}
