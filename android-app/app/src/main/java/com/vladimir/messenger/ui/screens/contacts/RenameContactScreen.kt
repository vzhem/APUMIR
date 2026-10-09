package com.vladimir.messenger.ui.screens.contacts

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.ApuGoldInk

/**
 * Локальное имя и сохранённый @никнейм контакта.
 *
 * Экран намеренно повторяет форму добавления контакта: обои, светлые карточки-
 * пузыри и золотое действие вместо стандартных серых полей Material.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameContactScreen(
    onRenamed: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: RenameContactViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.renamed) {
        if (uiState.renamed) onRenamed()
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
                        scrolledContainerColor = Color.Transparent,
                    ),
                    title = {
                        Text(
                            text = stringResource(R.string.rc_title),
                            fontWeight = FontWeight.Bold,
                        )
                    },
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
            RenameContactContent(
                paddingValues = paddingValues,
                uiState = uiState,
                onNewNameChanged = viewModel::onNewNameChanged,
                onNewUsernameChanged = viewModel::onNewUsernameChanged,
                onRenameClicked = viewModel::onRenameClicked,
            )
        }
    }
}

@Composable
private fun RenameContactContent(
    paddingValues: PaddingValues,
    uiState: RenameContactUiState,
    onNewNameChanged: (String) -> Unit,
    onNewUsernameChanged: (String) -> Unit,
    onRenameClicked: () -> Unit,
) {
    val canSave = uiState.newName.trim().isNotBlank() && !uiState.isLoading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Карточка сохраняет контекст: при длинном имени оно не уйдёт за край,
        // а сохранённый никнейм виден сразу, без отдельной серой строки.
        ApuBubble(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(ApuBubbleAccentColor.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = ApuBubbleAccentColor,
                        modifier = Modifier.size(25.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rc_edit_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = ApuBubbleMutedColor,
                    )
                    Text(
                        text = uiState.currentName.ifBlank { stringResource(R.string.rc_contact_fallback) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (uiState.currentUsername.isNotBlank()) {
                        Text(
                            text = "@${uiState.currentUsername.trimStart('@')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleAccentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.rc_hint_chat),
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
        }

        ApuBubble(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.rc_data_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.rc_data_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            }

            ApuFormTextField(
                value = uiState.newName,
                onValueChange = onNewNameChanged,
                label = stringResource(R.string.rc_name_label),
                placeholder = stringResource(R.string.rc_name_placeholder),
                isError = uiState.error != null,
                supportingText = uiState.error,
            )

            ApuFormTextField(
                value = uiState.newUsername.trimStart('@'),
                onValueChange = onNewUsernameChanged,
                label = stringResource(R.string.identity_nickname),
                placeholder = "nickname",
                leadingIcon = {
                    Text(
                        text = "@",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ApuBubbleAccentColor,
                    )
                },
            )

            ApuPremiumContentButton(
                onClick = onRenameClicked,
                style = DiagnosticsActionStyle.PRIMARY,
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(19.dp),
                        color = ApuGoldInk,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
