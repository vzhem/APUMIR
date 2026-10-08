package com.vladimir.messenger.ui.screens.contacts

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
                    title = { Text("Добавить контакт", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
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
                        title = "Ссылка-приглашение",
                        subtitle = "Вставьте ссылку, которую вам прислал собеседник.",
                    )
                    ApuFormTextField(
                        value = uiState.inviteLink,
                        onValueChange = viewModel::onInviteLinkChanged,
                        label = "Ссылка",
                        placeholder = "Вставьте ссылку или ключ",
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
                        label = "Имя контакта (необязательно)",
                        placeholder = "Например, Анна",
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(19.dp),
                                color = ApuGoldInk,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Добавить контакт", fontWeight = FontWeight.SemiBold)
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
                        title = "Найти по @никнейму",
                        subtitle = "Ищем только имена, которые уже встречались в сети APU.",
                    )
                    ApuSearchField(
                        value = uiState.nickQuery,
                        onValueChange = viewModel::onNickQueryChanged,
                        placeholder = "@никнейм",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (uiState.nickSearching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = ApuBubbleAccentColor,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Ищем в сети APU…",
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                    }
                    uiState.nickResults.forEach { entry ->
                        // Результат — самостоятельный маленький пузырь, а не
                        // голая строка с системной кнопкой посреди карточки.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    ApuBubbleAccentColor.copy(alpha = 0.08f),
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
                                Text(
                                    text = "@${entry.name}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = entry.ownerId.takeLast(8),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ApuBubbleMutedColor,
                                )
                            }
                            ApuTextAction(
                                label = "Добавить",
                                onClick = { viewModel.onAddByNicknameClicked(entry) },
                                enabled = !uiState.isLoading,
                            )
                        }
                    }
                    if (!uiState.nickSearching &&
                        uiState.nickQuery.trimStart('@').trim().isNotBlank() &&
                        uiState.nickResults.isEmpty()
                    ) {
                        Text(
                            text = "Никого не нашли. Проверьте написание никнейма или попросите ссылку-приглашение.",
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
                                text = "Сканировать QR-код",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Быстро добавьте человека рядом с вами",
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Открыть сканер",
                            tint = ApuBubbleAccentColor,
                        )
                    }
                }
            }
        }
    }
}

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
