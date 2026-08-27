package com.vladimir.messenger.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.FileTransferBubble
import com.vladimir.messenger.ui.components.MessageBubble
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private data class ChatRow(
    val key: String,
    val message: Message?,
    val transfer: FileTransferEntity?,
    val orderMs: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chatId: String,
    contactName: String,
    contactId: String = "",
    onBackClick: () -> Unit,
    onRenameClick: (contactId: String, currentName: String) -> Unit = { _, _ -> },
    viewModel: ChatDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var activeMessage by remember { mutableStateOf<Message?>(null) }
    var showCopyDialog by remember { mutableStateOf<Message?>(null) }

    // Сброс выделения и диалога копирования
    fun resetSelection() {
        activeMessage = null
        showCopyDialog = null
    }

    val context = LocalContext.current

    // F3: системный выбор файла (SAF) → зашифрованная подготовка → durable отправка
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::onFileSelected)
    }

    // F3: экспорт принятого файла — системный диалог «куда сохранить»
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        viewModel.onSaveTargetPicked(uri)
    }
    val pendingSave = uiState.pendingSave
    LaunchedEffect(pendingSave) {
        pendingSave?.let { transfer -> savePicker.launch(transfer.displayName) }
    }

    // Прокрутка к последнему сообщению
    LaunchedEffect(uiState.scrollToBottom, uiState.messages.size) {
        if (uiState.scrollToBottom && uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
            viewModel.onScrolledToBottom()
        }
    }

    // SnackBar для ошибок
    val snackbarHostState = remember { SnackbarHostState() }
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
                title = {
                    Column {
                        Text(
                            contactName,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text  = if (uiState.isContactOnline) "в сети" else "не в сети",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.isContactOnline)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                actions = {
                    if (contactId.isNotBlank()) {
                        IconButton(onClick = { onRenameClick(contactId, contactName) }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Rename",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { /* TODO: звонок */ }) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = "Звонок",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            MessageInputBar(
                text         = uiState.inputText,
                onTextChange = viewModel::onInputTextChanged,
                onSend       = viewModel::onSendMessage,
                isSending    = uiState.isSending,
                canAttach    = uiState.canSendAttachments,
                onAttach     = {
                    if (uiState.canSendAttachments) {
                        filePicker.launch(arrayOf("*/*"))
                    } else {
                        viewModel.onAttachmentsLocked()
                    }
                },
                isPreparingFile = uiState.isPreparingFile,
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { resetSelection() }
                    )
                },
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                uiState.messages.isEmpty() -> {
                    Column(
                        modifier            = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint     = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Сообщения зашифрованы E2E",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Напишите первое сообщение",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    val transfersByMessageId = remember(uiState.transfers) {
                        uiState.transfers.associateBy { it.messageId }
                    }
                    val chatRows = remember(uiState.messages, uiState.transfers) {
                        val knownMessageIds = uiState.messages.map { it.id }.toSet()
                        val rows = uiState.messages.map { message ->
                            ChatRow(
                                key = message.id,
                                message = message,
                                transfer = transfersByMessageId[message.id],
                                orderMs = message.timestamp,
                            )
                        } + uiState.transfers
                            .filter {
                                it.direction == "INCOMING" &&
                                    it.state != "COMPLETE" &&
                                    it.messageId !in knownMessageIds
                            }
                            .map { transfer ->
                                ChatRow(
                                    key = "t-${transfer.transferId}",
                                    message = null,
                                    transfer = transfer,
                                    orderMs = transfer.createdAtMs,
                                )
                            }
                        rows.sortedBy { it.orderMs }
                    }
                    LazyColumn(
                        state          = listState,
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        reverseLayout  = false,
                    ) {
                        items(
                            items = chatRows,
                            key   = { it.key },
                        ) { row ->
                            val transfer = row.transfer
                            if (transfer != null) {
                                FileTransferBubble(
                                    transfer = transfer,
                                    isFromMe = transfer.direction == "OUTGOING",
                                    onSaveClick = if (
                                        transfer.direction == "INCOMING" &&
                                        transfer.state == "COMPLETE"
                                    ) {
                                        { viewModel.requestSaveReceivedFile(transfer) }
                                    } else {
                                        null
                                    },
                                )
                            } else {
                                val message = row.message!!
                                MessageBubble(
                                    message = message,
                                    isSelected = activeMessage?.id == message.id,
                                    linkColor = if (message.isFromMe) Color.White else Color(0xFF4A90E2),
                                    onTap = {
                                        if (activeMessage?.id == message.id) {
                                            // Клик на уже выделенное → показать AlertDialog
                                            showCopyDialog = message
                                        } else {
                                            // Клик на другое сообщение → сбросить и выделить
                                            activeMessage = message
                                        }
                                    },
                                    onLongClick = {
                                        if (activeMessage?.id == message.id) {
                                            // Long press на выделенное → показать AlertDialog
                                            showCopyDialog = message
                                        } else {
                                            // Long press → включить выделение
                                            activeMessage = message
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // AlertDialog для копирования сообщения
    showCopyDialog?.let { message ->
        AlertDialog(
            onDismissRequest = { showCopyDialog = null },
            title = { Text("Копировать сообщение?") },
            text = { Text(message.content.take(100) + if (message.content.length > 100) "..." else "") },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Сообщение", message.content)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    showCopyDialog = null
                    resetSelection()
                }) {
                    Text("Копировать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCopyDialog = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    onAttach: () -> Unit = {},
    isPreparingFile: Boolean = false,
    canAttach: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier  = modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color     = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = onAttach,
                enabled = !isPreparingFile && !isSending,
                modifier = Modifier.size(48.dp),
            ) {
                if (isPreparingFile) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = if (canAttach) {
                            "Прикрепить файл"
                        } else {
                            "Вложения откроются с ранга «Круг друзей»"
                        },
                        tint = if (canAttach) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }

            TextField(
                value         = text,
                onValueChange = onTextChange,
                placeholder   = { Text("Сообщение...") },
                maxLines      = 5,
                colors        = TextFieldDefaults.colors(
                    focusedContainerColor   = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor   = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                shape    = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick  = onSend,
                enabled  = text.isNotBlank() && !isSending,
                modifier = Modifier.size(48.dp),
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier  = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color     = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Отправить",
                    )
                }
            }
        }
    }
}
