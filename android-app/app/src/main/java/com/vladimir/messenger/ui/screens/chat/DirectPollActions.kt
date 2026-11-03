package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.directpoll.DirectPollRepository
import com.vladimir.messenger.data.directpoll.DirectPollTally
import com.vladimir.messenger.data.group.PollSummary
import com.vladimir.messenger.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Опрос в личном чате (р252) — действия экрана, вынесенные из
 * `ChatDetailViewModel.kt`.
 *
 * Почему отдельно: ViewModel личного чата — god-файл с потолком, который можно
 * только опускать. Здесь же собраны три действия целиком, и правило ранга
 * (`FeatureRankPolicy.CREATE_POLL`, «Круг друзей», 3) остаётся в одном месте:
 * вопрос создаётся только автором нужного ранга, а голосовать и закрывать
 * можно по состоянию самого опроса.
 *
 * Формат обмена — [com.vladimir.messenger.data.directpoll.DirectPollWire]:
 * вопрос уходит обычным сообщением (очередь доставки, зеркало, старые версии
 * видят текст), варианты — служебным конвертом.
 */
internal object DirectPollActions {

    /**
     * Создать опрос: вопрос — обычное сообщение с заранее выданным id, чтобы
     * карточка ссылалась именно на него.
     *
     * @param lockedHint готовый текст отказа по рангу (пусто — ранг позволяет)
     */
    fun create(
        scope: CoroutineScope,
        polls: DirectPollRepository,
        chats: ChatRepository,
        chatId: String,
        peerId: String,
        lockedHint: String,
        question: String,
        options: List<String>,
        anonymous: Boolean,
        notify: (String) -> Unit,
        onSending: (Boolean) -> Unit,
        onSent: () -> Unit,
        invalidHint: String,
        sendErrorHint: (String?) -> String,
    ) {
        // Третий рубеж: диалог мог остаться открытым с прошлой версии.
        if (lockedHint.isNotEmpty()) {
            notify(lockedHint); return
        }
        val clean = DirectPollTally.normalize(question, options)
        if (clean == null) {
            notify(invalidHint); return
        }
        val (cleanQuestion, cleanOptions) = clean
        val messageId = UUID.randomUUID().toString()
        scope.launch {
            onSending(true)
            val envelope = polls.create(
                chatId = chatId,
                messageId = messageId,
                question = cleanQuestion,
                options = cleanOptions,
                anonymous = anonymous,
                creatorId = runCatching { RustBridge.nodeId() }.getOrNull().orEmpty(),
            ).getOrNull()
            if (envelope == null) {
                onSending(false)
                notify(invalidHint)
                return@launch
            }
            chats.sendMessage(chatId, "", cleanQuestion, fixedMessageId = messageId)
                .onSuccess {
                    onSending(false)
                    onSent()
                    // Неудача отправки вариантов не отменяет опрос: у автора он
                    // уже есть, а карточка досылается при следующем голосе.
                    polls.deliver(chatId, peerId, envelope)
                }
                .onFailure { e ->
                    onSending(false)
                    notify(sendErrorHint(e.message))
                }
        }
    }

    /** Голос за вариант (пустой список — отозвать): свой выбор виден сразу. */
    fun toggle(
        scope: CoroutineScope,
        polls: DirectPollRepository,
        chatId: String,
        peerId: String,
        poll: PollSummary,
        index: Int,
    ) {
        if (!DirectPollTally.canVote(poll.closed, poll.options.size, index)) return
        val next = DirectPollTally.toggle(poll.myChoices, index)
        scope.launch {
            val envelope = polls.vote(chatId, poll.pollId, next) ?: return@launch
            polls.deliver(chatId, peerId, envelope)
        }
    }

    /** Закрыть опрос: только автор вопроса. */
    fun close(
        scope: CoroutineScope,
        polls: DirectPollRepository,
        chatId: String,
        peerId: String,
        poll: PollSummary,
    ) {
        if (!DirectPollTally.canClose(poll.isMine, poll.closed)) return
        scope.launch {
            val envelope = polls.close(poll.pollId) ?: return@launch
            polls.deliver(chatId, peerId, envelope)
        }
    }
}
