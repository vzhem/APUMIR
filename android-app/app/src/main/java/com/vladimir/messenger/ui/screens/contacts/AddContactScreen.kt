package com.vladimir.messenger.ui.screens.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    initialInviteLink: String?,
    onContactAdded: () -> Unit,
    onBackClick: () -> Unit,
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
        if (uiState.contactAdded) {
            onContactAdded()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Add contact") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Content(
            paddingValues = paddingValues,
            uiState = uiState,
            onInviteLinkChanged = viewModel::onInviteLinkChanged,
            onDisplayNameChanged = viewModel::onDisplayNameChanged,
            onAddContactClicked = viewModel::onAddContactClicked,
        )
    }
}

@Composable
private fun Content(
    paddingValues: PaddingValues,
    uiState: AddContactUiState,
    onInviteLinkChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onAddContactClicked: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = "Paste invite link or contact fingerprint",
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.inviteLink,
            onValueChange = onInviteLinkChanged,
            label = { Text("Invite link") },
            placeholder = { Text("p2p://invite/... or fingerprint") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                uiState.error?.let { Text(it) }
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.displayName,
            onValueChange = onDisplayNameChanged,
            label = { Text("Имя контакта (опционально)") },
            placeholder = { Text("Анна, Стас, ...") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onAddContactClicked,
            enabled = uiState.inviteLink.isNotBlank() && !uiState.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(modifier = Modifier.height(0.dp))
                Text("Add contact")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Local network discovery and QR scan will be added in the next step.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}