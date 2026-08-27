package com.vladimir.messenger.ui.screens.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.util.ContactShareLink
import com.vladimir.messenger.util.OwnInvite
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vladimir.messenger.domain.model.Contact
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.ui.components.ContactCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onNavigateBack: () -> Unit,
    onContactClick: (Contact) -> Unit,
    onAddContactClick: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Контакты") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    // Пригласить друга — в один тап, прямо из списка контактов.
                    IconButton(
                        onClick = {
                            OwnInvite.link(context)?.let { link ->
                                AppShare.shareInvite(context, OwnInvite.displayName(context), link)
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Пригласить друга")
                    }
                    IconButton(onClick = onAddContactClick) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить контакт")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Пока нет контактов",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onAddContactClick) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Добавить контакт")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Пустой список - самое место, чтобы позвать первого друга.
                    OutlinedButton(
                        onClick = {
                            OwnInvite.link(context)?.let { link ->
                                AppShare.shareInvite(context, OwnInvite.displayName(context), link)
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Пригласить друга")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = contacts,
                    key = { it.id }
                ) { contact ->
                    // ContactCard expects Chat, create a minimal Chat from Contact
                    val ctx = LocalContext.current
                    ContactCard(
                        chat = Chat(
                            id = contact.id,
                            contactId = contact.id,
                            contactName = contact.displayName,
                            isContactOnline = contact.isOnline,
                        ),
                        onClick = { onContactClick(contact) },
                        onShareClick = {
                            try {
                                val link = ContactShareLink.build(contact.id, contact.displayName)
                                val send = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Мой контакт ${'$'}{contact.displayName} в APU. Открой ссылку для добавления:\n${'$'}{link}",
                                    )
                                    type = "text/plain"
                                }
                                ctx.startActivity(Intent.createChooser(send, "Поделиться контактом"))
                            } catch (_: Exception) {
                            }
                        },
                    )
                }
            }
        }
    }
}
