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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
        if (uiState.renamed) {
            onRenamed()
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
                title = { Text("Rename contact") },
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
            onNewNameChanged = viewModel::onNewNameChanged,
            onNewUsernameChanged = viewModel::onNewUsernameChanged,
            onRenameClicked = viewModel::onRenameClicked,
        )
    }
}

@Composable
private fun Content(
    paddingValues: PaddingValues,
    uiState: RenameContactUiState,
    onNewNameChanged: (String) -> Unit,
    onNewUsernameChanged: (String) -> Unit,
    onRenameClicked: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = "Current name: ${uiState.currentName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.newName,
            onValueChange = onNewNameChanged,
            label = { Text("New name") },
            placeholder = { Text("Enter new name") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                uiState.error?.let { Text(it) }
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Оригинальное имя через собаку - дополнительное к обычному имени.
        OutlinedTextField(
            value = uiState.newUsername,
            onValueChange = onNewUsernameChanged,
            label = { Text("@никнейм") },
            placeholder = { Text("evzhem") },
            prefix = { Text("@") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onRenameClicked,
            enabled = uiState.newName.trim().isNotBlank() && !uiState.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.height(0.dp))
                Text("Save")
            }
        }
    }
}
