package com.vladimir.messenger.ui.screens.devices

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.vladimir.messenger.ui.components.ApuPremiumButton
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.devices_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.devices_actions_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.devices_show_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.devices_show_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(12.dp))
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.devices_qr_description),
                modifier = Modifier.size(240.dp).align(Alignment.CenterHorizontally),
            )
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

        primary?.let { request ->
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.devices_joiner_device, request.deviceShort),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.devices_code_title), style = MaterialTheme.typography.titleMedium)
            Text(
                request.sas,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(stringResource(R.string.devices_code_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevicesButton(label = stringResource(R.string.devices_codes_match), onClick = {
                    scope.launch(Dispatchers.IO) { DevicePairingController.confirmPrimary() }
                    qrText = null
                })
                DevicesButton(label = stringResource(R.string.devices_codes_differ), onClick = {
                    DevicePairingController.rejectPrimary()
                    qrText = null
                }, primary = false)
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.devices_scan_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.devices_scan_hint), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        DevicesButton(label = stringResource(R.string.devices_scan_qr), onClick = onScanClick, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.devices_manual_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.devices_manual_hint), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = manualCode,
            onValueChange = { manualCode = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        DevicesButton(
            label = stringResource(R.string.devices_manual_button),
            onClick = { onManualCode(manualCode) },
            modifier = Modifier.fillMaxWidth(),
            primary = false,
        )

        if (primaryDone) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.devices_done))
            Spacer(Modifier.height(8.dp))
            DevicesButton(
                label = stringResource(R.string.devices_resend_grant),
                onClick = { DevicePairingController.resendGrant() },
                primary = false,
            )
        }

        joiner?.let { state ->
            Spacer(Modifier.height(12.dp))
            if (state.deviceShort.isNotBlank()) {
                Text(stringResource(R.string.devices_this_device, state.deviceShort))
                Spacer(Modifier.height(4.dp))
            }
            when (state.phase) {
                JoinerPhase.WAITING_ANSWER -> Text(stringResource(R.string.devices_waiting_answer))
                JoinerPhase.CONFIRM_CODE -> {
                    Text(stringResource(R.string.devices_code_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.sas.orEmpty(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(stringResource(R.string.devices_code_hint), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    DevicesButton(label = stringResource(R.string.devices_codes_match), onClick = {
                        scope.launch(Dispatchers.IO) { DevicePairingController.confirmJoinerCode() }
                    })
                }
                JoinerPhase.WAITING_GRANT -> Text(stringResource(R.string.devices_waiting_grant))
                JoinerPhase.DONE -> Text(stringResource(R.string.devices_done))
                JoinerPhase.FAILED -> Text(
                    stringResource(R.string.devices_failed) + (state.message?.let { ": $it" } ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                )
            }
            if (state.phase == JoinerPhase.DONE || state.phase == JoinerPhase.FAILED) {
                Spacer(Modifier.height(8.dp))
                DevicesButton(label = stringResource(R.string.devices_close), onClick = {
                    DevicePairingController.dismissJoiner()
                }, primary = false)
            } else {
                Spacer(Modifier.height(8.dp))
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
}

/** Фирменная кнопка экрана: основное действие или спокойная (GLASS). */
@Composable
private fun DevicesButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
) {
    ApuPremiumButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        style = if (primary) DiagnosticsActionStyle.PRIMARY else DiagnosticsActionStyle.GLASS,
    )
}
