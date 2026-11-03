package com.vladimir.messenger.ui.screens.chat

import android.content.Context
import com.vladimir.messenger.R
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Каталог GIF личного чата: открытие, ссылка на гифку и донабор её байтов.
 *
 * Вынесено из `ChatDetailViewModel.kt` при добавлении опросов (р252): файл
 * упирается в потолок god-файлов, а эти четыре действия читаются как одно
 * целое — «гифка из сети, а не файл по переписке». ViewModel остаётся тонкими
 * обёртками, поэтому экран и проверки не заметили переезда.
 */
internal object ChatGifActions {

    /** Всё, что нужно действиям: состояние, база и права ранга. */
    class Sink(
        val scope: CoroutineScope,
        val context: Context,
        val state: MutableStateFlow<ChatDetailUiState>,
        val chats: ChatRepository,
        val chatId: String,
        val gifAllowed: () -> Boolean,
    )

    /** Открыли каталог: своя библиотека и каталог сети — в один снимок. */
    fun onCatalogOpened(sink: Sink) {
        sink.scope.launch {
            runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.syncWithSwarm(
                    sink.context, sink.chats, force = false,
                )
            }
            val my = runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.entries(sink.context)
            }.getOrDefault(emptyList())
            val swarm = runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.swarmCatalog(sink.context)
            }.getOrDefault(emptyList())
            sink.state.update {
                it.copy(
                    gifTab = if (my.isNotEmpty() || swarm.isNotEmpty()) "swarm" else "external",
                    myGifs = my,
                    swarmGifs = swarm,
                )
            }
        }
    }

    fun setTab(sink: Sink, tab: String) {
        sink.state.update { it.copy(gifTab = tab) }
    }

    /**
     * Раунд 128: убедиться, что гифка есть в моей библиотеке (для карточки-
     * ссылки). Нет - тихо попросить у хранителей сети.
     */
    fun ensureRef(sink: Sink, sha256: String) {
        sink.scope.launch { ensureRefInternal(sink, sha256) }
    }

    suspend fun ensureRefInternal(sink: Sink, sha256: String) {
        val have = withContext(Dispatchers.IO) {
            com.vladimir.messenger.data.gif.GifLibrary.gifFile(sink.context, sha256)?.isFile == true
        }
        if (have) return
        val holders = runCatching {
            com.vladimir.messenger.data.gif.GifLibrary.swarmCatalog(sink.context)
        }.getOrDefault(emptyList())
            .firstOrNull { it.entry.sha256 == sha256 }?.holders.orEmpty()
        if (holders.isEmpty()) return
        com.vladimir.messenger.data.gif.GifLibrary.rememberWant(sha256)
        runCatching {
            com.vladimir.messenger.data.gif.GifLibrary.requestGif(
                sink.context, sink.chats, sha256, holders,
            )
        }
    }

    /**
     * Раунд 128: отправить ССЫЛКУ на гифку (от моего лица). Никаких файлов
     * по переписке: карточка одна, байты каждый телефон подтягивает тихо с
     * хранителей из каталога сети.
     */
    suspend fun sendRef(sink: Sink, sha256: String) {
        val chat = sink.chats.getChatById(sink.chatId)
            ?: error(sink.context.getString(R.string.cdv_chat_unavailable))
        val recipientId = chat.contactId
        val messageId = UUID.randomUUID().toString()
        // р245: это устройство без сети (зеркало)? Тогда ссылку отправляет
        // партнёр-активный: движка здесь нет, и строка иначе висела бы вечно.
        val viaMirror = sink.chats.sendGifRefViaMirror(
            chatId = sink.chatId,
            recipientId = recipientId,
            sha256 = sha256,
            messageId = messageId,
        )
        if (viaMirror) {
            sink.state.update { it.copy(scrollToBottom = true) }
            return
        }
        val sent = withContext(Dispatchers.IO) {
            runCatching {
                RustBridge.sendMessage(
                    messageId, sink.chatId, recipientId,
                    com.vladimir.messenger.data.gif.GifLibrary.refContent(sha256),
                )
            }.getOrDefault(false)
        }
        // Раунд 178: гифка больше НЕ ждёт собеседника. Отправилось - хорошо;
        // нет - карточка всё равно встаёт в телефонную очередь QUEUED_OFFLINE.
        sink.chats.insertGifRefMessage(
            chatId = sink.chatId,
            recipientId = recipientId,
            messageId = messageId,
            sha256 = sha256,
            timestamp = System.currentTimeMillis(),
            status = if (sent) "LOCAL_FILE"
            else com.vladimir.messenger.domain.model.MessageStatus.QUEUED_OFFLINE.name,
        )
        sink.state.update {
            it.copy(
                scrollToBottom = true,
                swarmStatus = if (sent) null
                else sink.context.getString(R.string.cd_gif_later),
            )
        }
        // Раунд 131: я мог только что скачать эту гифку - объявить каталог,
        // чтобы все узнали хранителя и смогли тихо забрать байты.
        runCatching {
            withContext(Dispatchers.IO) {
                com.vladimir.messenger.data.gif.GifLibrary.syncWithSwarm(
                    sink.context, sink.chats, force = false,
                )
            }
        }
        // Своя карточка тоже должна ожить: если гифки у меня нет - попросим.
        ensureRefInternal(sink, sha256)
    }
}
