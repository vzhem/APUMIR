package com.vladimir.messenger.ui.screens.devices

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.vladimir.messenger.R
import com.vladimir.messenger.data.pairing.DevicePairingController
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuPremiumButton
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.data.pairing.DevicePairingController.JoinerPhase
import com.vladimir.messenger.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-device pairing (P3). Every phone can do both actions on one screen:
 * - show a QR, compare the code with the other device, confirm;
 * - scan the other phone's QR, compare the code, confirm.
 * There is no fixed primary/new role here: the pressed button picks the direction.
 * The codes are the only trust step: the user checks them on both devices.
 */
@Composable
fun DevicesScreen(
    onBackClick: () -> Unit,
    onScanClick: () -> Unit,
    onManualCode: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val primary by DevicePairingController.primary.collectAsStateWithLifecycle()
    val joiner by DevicePairingController.joiner.collectAsStateWithLifecycle()
    val offerOpen by DevicePairingController.isOfferOpen.collectAsStateWithLifecycle()
    val primaryDone by DevicePairingController.primaryDone.collectAsStateWithLifecycle()
    var manualCode by remember { mutableStateOf("") }
    var qrText by remember { mutableStateOf<String?>(null) }
    var primaryConfirming by remember { mutableStateOf(false) }
    var joinerConfirming by remember { mutableStateOf(false) }
    // Роли на этом экране нет: любой телефон и показывает, и сканирует QR.
    // Кто сейчас «показывающий», а кто «сканирующий», определяет только нажатая кнопка.
    val qrBitmap = remember(qrText) { qrText?.let { QrCodeGenerator.generateQrCode(it, 800) } }

    DisposableEffect(Unit) {
        onDispose {
            // Leaving the screen closes an open offer; a joiner in progress keeps its state.
            if (DevicePairingController.isOfferOpen.value) {
                DevicePairingController.closeOffer()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.devices_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Общая подсказка - фирменным пузырём, как на других экранах.
        HintBubble(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                Text(
                    stringResource(R.string.devices_actions_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HintBubbleMutedColor,
                )
            }
        }

        DevicesSectionCard {
            Text(
                stringResource(R.string.devices_show_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.devices_show_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            DevicesButton(
                label = stringResource(R.string.devices_show_qr),
                onClick = {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            runCatching { DevicePairingController.openOffer() }.getOrNull()
                        }
                        if (text != null) {
                            qrText = text
                        } else {
                            Toast.makeText(context, context.getString(R.string.devices_error), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (offerOpen && qrBitmap != null && primary == null) {
                Spacer(Modifier.height(16.dp))
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.devices_qr_description),
                    modifier = Modifier.size(240.dp).align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.devices_qr_expiry),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                // Тот же код текстом: его можно скопировать и ввести вручную на
                // втором телефоне, если камера не справляется.
                SelectionContainer {
                    Text(
                        qrText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        primary?.let { request ->
            DevicesSectionCard(highlighted = true) {
                Text(
                    stringResource(R.string.devices_joiner_device, request.deviceShort),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.devices_code_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    request.sas,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.devices_code_hint), style = MaterialTheme.typography.bodyMedium)
                request.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.devices_confirm_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevicesButton(
                        label = stringResource(R.string.devices_codes_match),
                        onClick = {
                            if (!primaryConfirming) {
                                primaryConfirming = true
                                scope.launch(Dispatchers.IO) {
                                    val confirmed = DevicePairingController.confirmPrimary()
                                    withContext(Dispatchers.Main) {
                                        primaryConfirming = false
                                        if (confirmed) qrText = null
                                    }
                                }
                            }
                        },
                        enabled = !primaryConfirming,
                        progress = primaryConfirming,
                    )
                    DevicesButton(label = stringResource(R.string.devices_codes_differ), onClick = {
                        DevicePairingController.rejectPrimary()
                        qrText = null
                    }, primary = false, enabled = !primaryConfirming)
                }
            }
        }

        DevicesSectionCard {
            Text(
                stringResource(R.string.devices_scan_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.devices_scan_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            DevicesButton(label = stringResource(R.string.devices_scan_qr), onClick = onScanClick, modifier = Modifier.fillMaxWidth())
        }

        DevicesSectionCard {
            Text(
                stringResource(R.string.devices_manual_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.devices_manual_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            ApuBubbleField(
                value = manualCode,
                onValueChange = { manualCode = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.devices_manual_placeholder)) },
                singleLine = false,
                minLines = 2,
            )
            Spacer(Modifier.height(12.dp))
            DevicesButton(
                label = stringResource(R.string.devices_manual_button),
                onClick = { onManualCode(manualCode) },
                modifier = Modifier.fillMaxWidth(),
                primary = false,
            )
        }

        if (primaryDone) {
            DevicesSectionCard {
                Text(stringResource(R.string.devices_done), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                DevicesButton(
                    label = stringResource(R.string.devices_resend_grant),
                    onClick = { DevicePairingController.resendGrant() },
                    primary = false,
                )
            }
        }

        joiner?.let { state ->
            DevicesSectionCard {
                if (state.deviceShort.isNotBlank()) {
                    Text(stringResource(R.string.devices_this_device, state.deviceShort), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                when (state.phase) {
                    JoinerPhase.WAITING_ANSWER -> Text(stringResource(R.string.devices_waiting_answer), style = MaterialTheme.typography.bodyMedium)
                    JoinerPhase.CONFIRM_CODE -> {
                        Text(stringResource(R.string.devices_code_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            state.sas.orEmpty(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.devices_code_hint), style = MaterialTheme.typography.bodyMedium)
                        state.message?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        DevicesButton(
                            label = stringResource(R.string.devices_codes_match),
                            onClick = {
                                if (!joinerConfirming) {
                                    joinerConfirming = true
                                    scope.launch(Dispatchers.IO) {
                                        DevicePairingController.confirmJoinerCode()
                                        withContext(Dispatchers.Main) { joinerConfirming = false }
                                    }
                                }
                            },
                            enabled = !joinerConfirming,
                            progress = joinerConfirming,
                        )
                    }
                    JoinerPhase.WAITING_GRANT -> Text(stringResource(R.string.devices_waiting_grant), style = MaterialTheme.typography.bodyMedium)
                    JoinerPhase.DONE -> Text(stringResource(R.string.devices_done), style = MaterialTheme.typography.bodyMedium)
                    JoinerPhase.FAILED -> Text(
                        stringResource(R.string.devices_failed) + (state.message?.let { ": $it" } ?: ""),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp,
                    )
                }
                if (state.phase == JoinerPhase.DONE || state.phase == JoinerPhase.FAILED) {
                    Spacer(Modifier.height(12.dp))
                    DevicesButton(label = stringResource(R.string.devices_close), onClick = {
                        DevicePairingController.dismissJoiner()
                    }, primary = false)
                } else {
                    Spacer(Modifier.height(12.dp))
                    DevicesButton(label = stringResource(R.string.devices_resend_request), onClick = {
                        DevicePairingController.resendRequest()
                    }, primary = false)
                    Spacer(Modifier.height(8.dp))
                    DevicesButton(label = stringResource(R.string.devices_cancel), onClick = {
                        scope.launch(Dispatchers.IO) { DevicePairingController.cancel() }
                    }, primary = false)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/** Карточка раздела экрана: заголовок, пояснение и действие на одном пузыре. */
@Composable
private fun DevicesSectionCard(
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ApuSettingsCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        highlighted = highlighted,
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** Фирменная кнопка экрана: основное действие или спокойная (GLASS). */
@Composable
private fun DevicesButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    progress: Boolean = false,
) {
    ApuPremiumButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        style = if (primary) DiagnosticsActionStyle.PRIMARY else DiagnosticsActionStyle.GLASS,
        enabled = enabled,
        progress = progress,
    )
}
