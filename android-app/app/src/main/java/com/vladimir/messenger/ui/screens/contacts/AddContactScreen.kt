package com.vladimir.messenger.ui.screens.contacts

import com.vladimir.messenger.ui.components.ApuPremiumSpinner
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
// =============================================================================
// ADDCONTACTSCREEN.KT
// =============================================================================
// Добавление контакта в едином стиле APU: обои, светлые карточки-пузыри,
// золотые акценты и понятные действия. Три пути: ссылка, @никнейм или QR.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vladimir.messenger.data.local.entity.NicknameEntity
import java.text.DateFormat
import java.util.Date
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.ApuBubble
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleShape
import com.vladimir.messenger.ui.components.ApuFormTextField
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.ApuPremiumCheckbox
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.ApuGoldInk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    initialInviteLink: String?,
    onContactAdded: () -> Unit,
    onBackClick: () -> Unit,
    onScanQrClick: () -> Unit = {},
    viewModel: AddContactViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var autoSubmitDone by rememberSaveable { mutableStateOf(false) }
    var cleanupEntries by remember { mutableStateOf<List<NicknameEntity>?>(null) }
    var cleanupSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var forgetContacts by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialInviteLink) {
        if (!initialInviteLink.isNullOrBlank() && !autoSubmitDone) {
            viewModel.onInviteLinkChanged(initialInviteLink)
            viewModel.onAddContactClicked()
            autoSubmitDone = true
        }
    }

    LaunchedEffect(uiState.contactAdded) {
        if (uiState.contactAdded) onContactAdded()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.notice) {
        uiState.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearNotice()
        }
    }

    val nicknameGroups = uiState.nickResults
        .groupBy { it.name.trim().lowercase() }
        .values
        .map { group -> group.sortedWith(compareBy({ if (it.registeredAtMs > 0) 0 else 1 }, { it.registeredAtMs }, { it.ownerId })) }
        .sortedBy { group ->
            group.mapNotNull { it.registeredAtMs.takeIf { time -> time > 0 } }.minOrNull() ?: Long.MAX_VALUE
        }
    val cleanupCandidates = nicknameGroups
        .flatten()
        .filter { it.ownerId in uiState.sameDeviceOwnerIds }

    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        // Прокрутка не должна красить панель: под ней обои APU.
                        scrolledContainerColor = Color.Transparent,
                    ),
                    title = { Text(stringResource(R.string.chat_add_contact), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---------------- Ссылка-приглашение ----------------
                ApuBubble(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ApuAddContactSectionHeader(
                        icon = Icons.Default.Link,
                        title = stringResource(R.string.chat_invite_link_label),
                        subtitle = stringResource(R.string.ac_invite_hint),
                    )
                    ApuFormTextField(
                        value = uiState.inviteLink,
                        onValueChange = viewModel::onInviteLinkChanged,
                        label = stringResource(R.string.groups_link),
                        placeholder = stringResource(R.string.ac_link_placeholder),
                        isError = uiState.error != null,
                        supportingText = uiState.error,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = ApuBubbleAccentColor,
                            )
                        },
                    )
                    ApuFormTextField(
                        value = uiState.displayName,
                        onValueChange = viewModel::onDisplayNameChanged,
                        label = stringResource(R.string.ac_name_label),
                        placeholder = stringResource(R.string.ac_name_placeholder),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = ApuBubbleAccentColor,
                            )
                        },
                    )
                    ApuPremiumContentButton(
                        onClick = viewModel::onAddContactClicked,
                        style = DiagnosticsActionStyle.PRIMARY,
                        enabled = uiState.inviteLink.isNotBlank() && !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.isLoading) {
                            ApuPremiumSpinner(
                                modifier = Modifier.size(19.dp),
                                color = ApuGoldInk,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.chat_add_contact), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // ---------------- Поиск по @никнейму ----------------
                ApuBubble(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ApuAddContactSectionHeader(
                        icon = Icons.Default.Search,
                        title = stringResource(R.string.ac_find_title),
                        subtitle = stringResource(R.string.ac_find_hint),
                    )
                    ApuSearchField(
                        value = uiState.nickQuery,
                        onValueChange = viewModel::onNickQueryChanged,
                        placeholder = stringResource(R.string.ac_username_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (uiState.nickSearching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ApuPremiumSpinner(
                                modifier = Modifier.size(16.dp),
                                color = ApuBubbleAccentColor,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.ac_searching),
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                    }
                    nicknameGroups.forEach { group ->
                        if (group.size > 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = ApuBubbleAccentColor,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    text = stringResource(R.string.ac_registry_group, group.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ApuBubbleAccentColor,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        group.forEach { entry ->
                            val isCurrent = entry.ownerId == uiState.currentOwnerId
                            val isOldOnThisDevice = entry.ownerId in uiState.sameDeviceOwnerIds
                            // Результат — самостоятельный маленький пузырь, а не
                            // голая строка с системной кнопкой посреди карточки.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isCurrent) ApuGoldInk.copy(alpha = 0.12f)
                                        else ApuBubbleAccentColor.copy(alpha = 0.08f),
                                        RoundedCornerShape(14.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(ApuBubbleAccentColor.copy(alpha = 0.14f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AlternateEmail,
                                        contentDescription = null,
                                        tint = ApuBubbleAccentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "@${entry.name}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isCurrent) {
                                            Spacer(Modifier.width(7.dp))
                                            Text(
                                                text = stringResource(R.string.ac_current_address),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ApuGoldInk,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    Text(
                                        text = entry.ownerId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ApuBubbleMutedColor,
                                        softWrap = true,
                                    )
                                    Text(
                                        text = if (entry.registeredAtMs > 0) {
                                            stringResource(
                                                R.string.ac_registered_at,
                                                formatRegistrationTime(entry.registeredAtMs),
                                            )
                                        } else {
                                            stringResource(R.string.ac_registered_unknown)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ApuBubbleMutedColor,
                                    )
                                    if (isOldOnThisDevice) {
                                        Text(
                                            text = stringResource(R.string.ac_old_same_device),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ApuGoldInk,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                if (!isCurrent && !isOldOnThisDevice) {
                                    ApuTextAction(
                                        label = stringResource(R.string.mtproxy_add_btn),
                                        onClick = { viewModel.onAddByNicknameClicked(entry) },
                                        enabled = !uiState.isLoading,
                                    )
                                }
                            }
                        }
                    }
                    if (cleanupCandidates.isNotEmpty()) {
                        ApuTextAction(
                            label = stringResource(R.string.ac_cleanup_old, cleanupCandidates.size),
                            onClick = {
                                cleanupEntries = cleanupCandidates
                                cleanupSelection = cleanupCandidates.map { it.ownerId }.toSet()
                                // Cleanup is meant to stop delivery to dead
                                // addresses; linked contacts/chats are included
                                // by default, with the explicit opt-out below.
                                forgetContacts = true
                            },
                            enabled = !uiState.isLoading,
                        )
                    }
                    if (!uiState.nickSearching &&
                        uiState.nickQuery.trimStart('@').trim().isNotBlank() &&
                        uiState.nickResults.isEmpty()
                    ) {
                        Text(
                            text = stringResource(R.string.ac_nobody_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                }

                // ---------------- QR-код ----------------
                ApuBubble(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onScanQrClick),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(ApuBubbleAccentColor.copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = ApuBubbleAccentColor,
                                modifier = Modifier.size(25.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ac_scan_qr),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(R.string.ac_scan_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.ac_open_scanner),
                            tint = ApuBubbleAccentColor,
                        )
                    }
                }
            }
        }

        cleanupEntries?.let { candidates ->
            val selected = candidates.filter { it.ownerId in cleanupSelection }
            ApuSettingsDialog(
                onDismissRequest = { cleanupEntries = null },
                icon = {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = ApuGoldInk,
                    )
                },
                title = { Text(stringResource(R.string.ac_cleanup_title), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.ac_cleanup_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                        candidates.forEach { entry ->
                            val checked = entry.ownerId in cleanupSelection
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ApuPremiumCheckbox(
                                    checked = checked,
                                    onCheckedChange = { value ->
                                        cleanupSelection = if (value) {
                                            cleanupSelection + entry.ownerId
                                        } else {
                                            cleanupSelection - entry.ownerId
                                        }
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "@${entry.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = entry.ownerId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ApuBubbleMutedColor,
                                        softWrap = true,
                                    )
                                    Text(
                                        text = if (entry.registeredAtMs > 0) {
                                            stringResource(
                                                R.string.ac_registered_at,
                                                formatRegistrationTime(entry.registeredAtMs),
                                            )
                                        } else {
                                            stringResource(R.string.ac_registered_unknown)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ApuBubbleMutedColor,
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ApuPremiumCheckbox(
                                checked = forgetContacts,
                                onCheckedChange = { forgetContacts = it },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.ac_cleanup_forget_contacts),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.ac_cleanup_forget_contacts_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ApuBubbleMutedColor,
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    ApuTextAction(
                        label = stringResource(R.string.ac_cleanup_cancel),
                        onClick = { cleanupEntries = null },
                    )
                },
                confirmButton = {
                    ApuTextAction(
                        label = stringResource(R.string.ac_cleanup_remove),
                        onClick = {
                            viewModel.cleanupSameDeviceEntries(selected, forgetContacts)
                            cleanupEntries = null
                        },
                        enabled = selected.isNotEmpty(),
                        danger = true,
                    )
                },
            )
        }
    }
}

private fun formatRegistrationTime(atMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(atMs))

/** Единая шапка карточек на формах: иконка, заголовок и короткое пояснение. */
@Composable
private fun ApuAddContactSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(ApuBubbleAccentColor.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ApuBubbleAccentColor,
                modifier = Modifier.size(23.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
        }
    }
}
