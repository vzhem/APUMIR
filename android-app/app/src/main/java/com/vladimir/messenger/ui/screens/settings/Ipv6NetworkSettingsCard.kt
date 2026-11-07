package com.vladimir.messenger.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.swarm.IPV6_RECOMMENDED_SHOP_URL
import com.vladimir.messenger.data.swarm.Ipv6NetworkSettings
import com.vladimir.messenger.data.swarm.Ipv6ProxyParser
import com.vladimir.messenger.data.swarm.Ipv6ProxyProbeResult
import com.vladimir.messenger.data.swarm.Ipv6ProxyProtocol
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuFormTextField
import com.vladimir.messenger.ui.components.ApuPremiumButton
import com.vladimir.messenger.ui.components.ApuPremiumChoiceRow
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuSettingsItem
import com.vladimir.messenger.ui.components.ApuSettingsSectionTitle
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Ipv6NetworkSettingsUi"

private data class Ipv6StatusFeedback(
    val text: String,
    val isError: Boolean,
)

/**
 * Два соседних блока в «Настройки → Сеть»:
 *  1) «Настройка IPv6» — ввод реквизитов купленного IPv6-прокси (SOCKS5 / HTTP(S),
 *     вставка одной строкой, живая проверка и мгновенное переключение приложения);
 *  2) «Где приобрести IPv6» — прямая рекомендация `https://ru.shopproxy.net/buy-proxy/ipv6/`
 *     с явным пояснением, что подходит и любой другой провайдер или свой сервер.
 */
@Composable
fun Ipv6NetworkSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val saved by Ipv6NetworkSettings.config.collectAsStateWithLifecycle()

    var draftProtocol by remember { mutableStateOf(Ipv6ProxyProtocol.SOCKS5) }
    var draftHost by remember { mutableStateOf("") }
    var draftPort by remember { mutableStateOf("") }
    var draftUser by remember { mutableStateOf("") }
    var draftPass by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Ipv6StatusFeedback?>(null) }
    var initializedFields by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val current = withContext(Dispatchers.IO) { Ipv6NetworkSettings.initialize(context) }
        draftProtocol = current.protocol
        draftHost = current.host
        draftPort = if (current.port > 0) current.port.toString() else ""
        draftUser = current.username
        draftPass = current.password
        initializedFields = true
    }

    LaunchedEffect(saved) {
        if (initializedFields && !saved.isConfigured && draftHost.isEmpty() && draftPort.isEmpty()) {
            draftProtocol = Ipv6ProxyProtocol.SOCKS5
        }
    }

    val statusSubtitle = when {
        saved.isActive -> stringResource(R.string.ipv6_proxy_status_active_tpl, saved.safeSummary())
        saved.isConfigured -> stringResource(R.string.ipv6_proxy_status_paused_tpl, saved.safeSummary())
        else -> stringResource(R.string.ipv6_proxy_status_empty)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ApuSettingsSectionTitle(stringResource(R.string.section_ipv6_network))
        ApuSettingsCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            ApuSettingsItem(
                icon = Icons.Default.Dns,
                title = stringResource(R.string.ipv6_proxy_enable_title),
                subtitle = statusSubtitle,
                trailingContent = {
                    ApuPremiumSwitch(
                        checked = saved.isActive,
                        onCheckedChange = { wantEnabled ->
                            if (!wantEnabled) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Ipv6NetworkSettings.setEnabled(context, false)
                                    }
                                    feedback = null
                                }
                            } else {
                                val candidate = Ipv6ProxyParser.fromFields(
                                    enabled = true,
                                    protocol = draftProtocol,
                                    hostInput = draftHost,
                                    portInput = draftPort,
                                    usernameInput = draftUser,
                                    passwordInput = draftPass,
                                )
                                if (candidate != null) {
                                    draftProtocol = candidate.protocol
                                    draftHost = candidate.host
                                    draftPort = candidate.port.toString()
                                    draftUser = candidate.username
                                    draftPass = candidate.password
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            Ipv6NetworkSettings.saveAndActivate(context, candidate)
                                        }
                                    }
                                } else if (saved.isConfigured) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            Ipv6NetworkSettings.setEnabled(context, true)
                                        }
                                    }
                                } else {
                                    feedback = Ipv6StatusFeedback(
                                        text = context.getString(R.string.ipv6_proxy_msg_invalid),
                                        isError = true,
                                    )
                                }
                            }
                        },
                    )
                },
            )
            ApuSettingsDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.ipv6_proxy_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
                ApuPremiumButton(
                    label = stringResource(R.string.ipv6_proxy_paste_btn),
                    icon = Icons.Default.ContentPaste,
                    style = DiagnosticsActionStyle.GLASS,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val raw = readClipboardSafely(clipboard)
                        if (raw.isNullOrBlank()) {
                            feedback = Ipv6StatusFeedback(
                                text = context.getString(R.string.ipv6_proxy_msg_clipboard_empty),
                                isError = true,
                            )
                        } else {
                            val parsed = Ipv6ProxyParser.parseQuickInput(raw, draftProtocol)
                            if (parsed == null) {
                                feedback = Ipv6StatusFeedback(
                                    text = context.getString(R.string.ipv6_proxy_msg_bad_paste),
                                    isError = true,
                                )
                            } else {
                                draftProtocol = parsed.protocol
                                draftHost = parsed.host
                                draftPort = parsed.port.toString()
                                draftUser = parsed.username
                                draftPass = parsed.password
                                feedback = Ipv6StatusFeedback(
                                    text = context.getString(R.string.ipv6_proxy_msg_pasted),
                                    isError = false,
                                )
                            }
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.ipv6_proxy_paste_formats),
                    style = MaterialTheme.typography.labelSmall,
                    color = ApuBubbleMutedColor,
                )
                Text(
                    text = stringResource(R.string.ipv6_proxy_protocol_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = ApuBubbleMutedColor,
                )
                ApuPremiumChoiceRow(
                    title = stringResource(R.string.ipv6_proxy_protocol_socks5),
                    subtitle = stringResource(R.string.ipv6_proxy_protocol_socks5_hint),
                    selected = draftProtocol == Ipv6ProxyProtocol.SOCKS5,
                    onClick = {
                        draftProtocol = Ipv6ProxyProtocol.SOCKS5
                        feedback = null
                    },
                )
                ApuPremiumChoiceRow(
                    title = stringResource(R.string.ipv6_proxy_protocol_http),
                    subtitle = stringResource(R.string.ipv6_proxy_protocol_http_hint),
                    selected = draftProtocol == Ipv6ProxyProtocol.HTTP,
                    onClick = {
                        draftProtocol = Ipv6ProxyProtocol.HTTP
                        feedback = null
                    },
                )
                ApuFormTextField(
                    value = draftHost,
                    onValueChange = {
                        draftHost = it
                        feedback = null
                    },
                    label = stringResource(R.string.ipv6_proxy_host_label),
                    placeholder = stringResource(R.string.ipv6_proxy_host_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                ApuFormTextField(
                    value = draftPort,
                    onValueChange = {
                        draftPort = it.filter { ch -> ch.isDigit() }.take(5)
                        feedback = null
                    },
                    label = stringResource(R.string.ipv6_proxy_port_label),
                    placeholder = stringResource(R.string.ipv6_proxy_port_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                ApuFormTextField(
                    value = draftUser,
                    onValueChange = {
                        draftUser = it
                        feedback = null
                    },
                    label = stringResource(R.string.ipv6_proxy_user_label),
                    placeholder = stringResource(R.string.ipv6_proxy_user_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                ApuFormTextField(
                    value = draftPass,
                    onValueChange = {
                        draftPass = it
                        feedback = null
                    },
                    label = stringResource(R.string.ipv6_proxy_pass_label),
                    placeholder = stringResource(R.string.ipv6_proxy_pass_placeholder),
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draftPass.isNotEmpty()) {
                    ApuTextAction(
                        label = stringResource(
                            if (showPassword) R.string.ipv6_proxy_hide_pass else R.string.ipv6_proxy_show_pass
                        ),
                        onClick = { showPassword = !showPassword },
                    )
                }

                feedback?.let { msg ->
                    Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (msg.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }

                ApuPremiumButton(
                    label = stringResource(R.string.ipv6_proxy_save_btn),
                    icon = Icons.Default.Check,
                    style = DiagnosticsActionStyle.PRIMARY,
                    progress = isSaving,
                    enabled = !isSaving && !isChecking,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val parsed = Ipv6ProxyParser.fromFields(
                            enabled = true,
                            protocol = draftProtocol,
                            hostInput = draftHost,
                            portInput = draftPort,
                            usernameInput = draftUser,
                            passwordInput = draftPass,
                        )
                        if (parsed == null) {
                            feedback = Ipv6StatusFeedback(
                                text = context.getString(R.string.ipv6_proxy_msg_invalid),
                                isError = true,
                            )
                            return@ApuPremiumButton
                        }
                        draftProtocol = parsed.protocol
                        draftHost = parsed.host
                        draftPort = parsed.port.toString()
                        draftUser = parsed.username
                        draftPass = parsed.password
                        isSaving = true
                        feedback = Ipv6StatusFeedback(
                            text = context.getString(R.string.ipv6_proxy_msg_saved_checking),
                            isError = false,
                        )
                        scope.launch {
                            val probeResult = withContext(Dispatchers.IO) {
                                Ipv6NetworkSettings.saveAndActivate(context, parsed)
                                Ipv6NetworkSettings.probe(parsed)
                            }
                            isSaving = false
                            feedback = when (probeResult) {
                                is Ipv6ProxyProbeResult.Ok -> Ipv6StatusFeedback(
                                    text = context.getString(R.string.ipv6_proxy_msg_saved_ok_tpl, probeResult.latencyMs),
                                    isError = false,
                                )
                                Ipv6ProxyProbeResult.AuthFailed -> Ipv6StatusFeedback(
                                    text = context.getString(R.string.ipv6_proxy_msg_saved_auth_fail),
                                    isError = true,
                                )
                                is Ipv6ProxyProbeResult.Unreachable -> Ipv6StatusFeedback(
                                    text = context.getString(R.string.ipv6_proxy_msg_saved_warn_tpl, probeResult.reason),
                                    isError = true,
                                )
                                Ipv6ProxyProbeResult.InvalidConfig -> Ipv6StatusFeedback(
                                    text = context.getString(R.string.ipv6_proxy_msg_invalid),
                                    isError = true,
                                )
                            }
                        }
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ApuPremiumButton(
                        label = stringResource(R.string.ipv6_proxy_check_btn),
                        icon = Icons.Default.NetworkCheck,
                        style = DiagnosticsActionStyle.GLASS,
                        progress = isChecking,
                        enabled = !isSaving && !isChecking,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val parsed = Ipv6ProxyParser.fromFields(
                                enabled = true,
                                protocol = draftProtocol,
                                hostInput = draftHost,
                                portInput = draftPort,
                                usernameInput = draftUser,
                                passwordInput = draftPass,
                            )
                            if (parsed == null) {
                                feedback = Ipv6StatusFeedback(
                                    text = context.getString(R.string.ipv6_proxy_msg_invalid),
                                    isError = true,
                                )
                                return@ApuPremiumButton
                            }
                            isChecking = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    Ipv6NetworkSettings.probe(parsed)
                                }
                                isChecking = false
                                feedback = when (result) {
                                    is Ipv6ProxyProbeResult.Ok -> Ipv6StatusFeedback(
                                        text = context.getString(R.string.ipv6_proxy_msg_probe_ok_tpl, result.latencyMs),
                                        isError = false,
                                    )
                                    Ipv6ProxyProbeResult.AuthFailed -> Ipv6StatusFeedback(
                                        text = context.getString(R.string.ipv6_proxy_msg_probe_auth_fail),
                                        isError = true,
                                    )
                                    is Ipv6ProxyProbeResult.Unreachable -> Ipv6StatusFeedback(
                                        text = context.getString(R.string.ipv6_proxy_msg_probe_fail_tpl, result.reason),
                                        isError = true,
                                    )
                                    Ipv6ProxyProbeResult.InvalidConfig -> Ipv6StatusFeedback(
                                        text = context.getString(R.string.ipv6_proxy_msg_invalid),
                                        isError = true,
                                    )
                                }
                            }
                        },
                    )
                    if (saved.isConfigured || draftHost.isNotEmpty() || draftPort.isNotEmpty()) {
                        ApuTextAction(
                            label = stringResource(R.string.ipv6_proxy_clear_btn),
                            danger = true,
                            onClick = {
                                draftHost = ""
                                draftPort = ""
                                draftUser = ""
                                draftPass = ""
                                draftProtocol = Ipv6ProxyProtocol.SOCKS5
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Ipv6NetworkSettings.clear(context)
                                    }
                                    feedback = Ipv6StatusFeedback(
                                        text = context.getString(R.string.ipv6_proxy_msg_cleared),
                                        isError = false,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        ApuSettingsSectionTitle(stringResource(R.string.section_ipv6_recommendations))
        ApuSettingsCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            ApuSettingsItem(
                icon = Icons.Default.Public,
                title = stringResource(R.string.ipv6_rec_shop_title),
                subtitle = stringResource(R.string.ipv6_rec_shop_subtitle),
                onClick = { openExternalUrl(context, IPV6_RECOMMENDED_SHOP_URL) },
            )
            ApuSettingsDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.ipv6_rec_any_provider_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ApuPremiumButton(
                        label = stringResource(R.string.ipv6_rec_open_btn),
                        icon = Icons.Default.Link,
                        style = DiagnosticsActionStyle.PRIMARY,
                        modifier = Modifier.weight(1f),
                        onClick = { openExternalUrl(context, IPV6_RECOMMENDED_SHOP_URL) },
                    )
                    ApuPremiumButton(
                        label = stringResource(R.string.ipv6_rec_copy_btn),
                        icon = Icons.Default.ContentCopy,
                        style = DiagnosticsActionStyle.GLASS,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            try {
                                clipboard.setText(AnnotatedString(IPV6_RECOMMENDED_SHOP_URL))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.ipv6_rec_copied),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } catch (e: Exception) {
                                Log.w(TAG, "Copy shop URL failed: ${e.message}")
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun readClipboardSafely(
    clipboard: androidx.compose.ui.platform.ClipboardManager,
): String? =
    try {
        clipboard.getText()?.text
    } catch (e: Exception) {
        Log.w(TAG, "Clipboard read failed: ${e.message}")
        null
    }

private fun openExternalUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to open browser for $url: ${e.message}")
    }
}
