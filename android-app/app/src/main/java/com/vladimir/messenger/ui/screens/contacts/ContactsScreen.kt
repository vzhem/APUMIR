package com.vladimir.messenger.ui.screens.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.util.ContactShareLink
import com.vladimir.messenger.util.OwnInvite
import androidx.compose.ui.Alignment
import com.vladimir.messenger.ui.components.ChatWallpaper
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vladimir.messenger.domain.model.Contact
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.ui.components.ContactCard
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleTextColor

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

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
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
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            var query by remember { mutableStateOf("") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Поиск: имя или @никнейм") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
            val shown = remember(contacts, query) {
                val q = query.trim().lowercase()
                val qNick = q.removePrefix("@")
                if (q.isEmpty()) contacts
                else contacts.filter {
                    it.displayName.lowercase().contains(q) ||
                        it.username.lowercase().contains(qNick)
                }
            }
            if (shown.isEmpty() && contacts.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Раунд 48: подсказка в пузыре HintBubble - на обоях и в
                    // ночной теме голый текст не читался.
                    HintBubble {
                        Text(
                            "Никого не нашли по запросу «$query»",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HintBubbleTextColor,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Раунд 48: пустой список контактов тоже в пузыре HintBubble.
                HintBubble {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                    Text(
                        text = "Пока нет контактов",
                        style = MaterialTheme.typography.titleMedium,
                        color = HintBubbleTextColor
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
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = shown,
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
                        username = contact.username,
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
    }
}
