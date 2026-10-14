package com.vladimir.messenger.data.story

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.swarm.SwarmBudget
import com.vladimir.messenger.data.swarm.SwarmLane
import com.vladimir.messenger.ui.theme.UsernameHolder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Доставка откликов на истории: значки, комментарии и сводки.
 *
 * Хранилище и правила - [StoryFeedbackStore] и [StoryFeedbackOutbox]; здесь
 * только сеть. Отклик уходит тем же путём, что и пакет истории
 * (`ChatRepository.sendStoryPacket`), поэтому у него те же свойства: шифрование
 * ядра, релей для офлайн-собеседника и досылка повторными волнами.
 *
 * Рассылка сводки зрителям - служебный веер, поэтому он идёт через полосу
 * [SwarmLane.SIGNAL]: при пустом бюджете сводка откладывается, но содержимое
 * (сами истории, сообщения, файлы) не задерживается. Ровно так же устроены
 * реакции на сообщения и просмотры постов.
 */
@Singleton
class StoryFeedbackRepository @Inject constructor(
    private val chatRepository: ChatRepository,
    private val swarmBudget: SwarmBudget,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pumpMutex = Mutex()
    private var seenVersion = -1L

    init {
        // Отклик принят - досылаем сразу, не дожидаясь планового цикла службы:
        // значок у соседей должен появиться за секунды, а не за полминуты.
        scope.launch {
            StoryFeedbackStore.changes.collect { version ->
                if (version == seenVersion) return@collect
                seenVersion = version
                delay(StoryFeedbackOutbox.BROADCAST_DEBOUNCE_MS)
                try {
                    pump()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "story feedback pump failed: " + error.message)
                }
            }
        }
    }

    /**
     * Поставить или снять значок на истории. Возвращает true - поставлен,
     * false - снят, null - действие не принято (ядро не готово, пустой значок).
     */
    suspend fun react(story: StoryStore.Story, emoji: String): Boolean? = withContext(Dispatchers.IO) {
        val ownId = RustBridge.nodeId().orEmpty()
        if (ownId.isBlank()) return@withContext null
        val added = StoryFeedbackStore.toggleReaction(story, emoji, ownId, profileName())
        if (added != null) pumpNow()
        added
    }

    /** Написать комментарий к истории. true - комментарий принят и досылается. */
    suspend fun comment(story: StoryStore.Story, body: String): Boolean = withContext(Dispatchers.IO) {
        val ownId = RustBridge.nodeId().orEmpty()
        if (ownId.isBlank()) return@withContext false
        val commentId = StoryFeedbackStore.addComment(story, body, ownId, profileName())
        if (commentId == null) return@withContext false
        pumpNow()
        true
    }

    /**
     * История открыта: если откликов у нас нет, попросить сводку у автора.
     * Просьба ограничена по частоте ([StoryFeedbackOutbox.ASK_COOLDOWN_MS]).
     */
    suspend fun onStoryOpened(story: StoryStore.Story) {
        withContext(Dispatchers.IO) {
            val ownId = RustBridge.nodeId().orEmpty()
            if (ownId.isBlank()) return@withContext
            if (!StoryFeedbackStore.requestSummary(story, ownId, profileName())) return@withContext
            pumpNow()
        }
    }

    /**
     * Цикл досылки: свои отклики - автору, сводки - зрителям.
     *
     * Вызывается из службы ядра вместе с насосом историй и после каждого
     * локального изменения; повторные вызовы сериализует [pumpMutex].
     */
    suspend fun pump() {
        pumpMutex.withLock {
            val ownId = RustBridge.nodeId().orEmpty()
            if (ownId.isBlank()) return
            StoryFeedbackStore.rememberOwnId(ownId)
            val now = System.currentTimeMillis()
            // Книга откликов живёт столько же, сколько лента историй.
            runCatching {
                StoryFeedbackStore.retain(StoryStore.active(now).map { it.id }.toSet(), now)
            }.onFailure { Log.w(TAG, "story feedback retain failed: " + it.message) }
            val tasks = StoryFeedbackStore.dueTasks(now)
            for (task in tasks) {
                val atMs = System.currentTimeMillis()
                // null - задача отложена сама (сводка ещё не созрела): исход ей
                // не записываем, иначе она была бы снята или отложена на минуту.
                val accepted = runCatching { dispatch(task, ownId, atMs) }
                    .getOrElse {
                        Log.w(TAG, "story feedback send failed: " + it.message)
                        false
                    }
                if (accepted != null) StoryFeedbackStore.taskResult(task, accepted, System.currentTimeMillis())
            }
        }
    }

    /**
     * Отправить одну задачу. true - принято, false - повтор через минуту,
     * null - задача отложена на свой срок и исход ей записывать не надо.
     */
    private suspend fun dispatch(
        task: StoryFeedbackOutbox.Task,
        ownId: String,
        nowMs: Long,
    ): Boolean? {
        val outgoing = StoryFeedbackStore.outgoing(task, ownId, profileName())
        // Текста нет: история исчезла, значок снят или комментарий вытеснен.
        // Задачу считаем выполненной - досылать нечего.
        if (outgoing == null) return true
        return when (task.kind) {
            StoryFeedbackOutbox.Kind.BROADCAST -> broadcast(task, outgoing, ownId, nowMs)
            StoryFeedbackOutbox.Kind.UNICAST -> {
                if (task.peer.isBlank()) return true
                if (!swarmBudget.tryAcquire(SwarmLane.SIGNAL)) return false
                chatRepository.sendStoryPacket(task.peer, outgoing.content, outgoing.transportId)
            }

            StoryFeedbackOutbox.Kind.REACT, StoryFeedbackOutbox.Kind.COMMENT,
            StoryFeedbackOutbox.Kind.ASK -> {
                if (task.peer.isBlank()) return true
                // Своё действие человека уходит без очереди роя: это не веер.
                chatRepository.sendStoryPacket(task.peer, outgoing.content, outgoing.transportId)
            }
        }
    }

    /** Сводка автора всем зрителям, кроме тех, кто свой отклик уже применил. */
    private suspend fun broadcast(
        task: StoryFeedbackOutbox.Task,
        outgoing: StoryFeedbackStore.Outgoing,
        ownId: String,
        nowMs: Long,
    ): Boolean? {
        if (!StoryFeedbackStore.broadcastDue(task.storyId, nowMs)) {
            // Сводка ещё не созрела: ждём остаток окна между волнами, а не
            // минуту повтора - иначе активная история рассылалась бы раз в минуту.
            StoryFeedbackStore.taskDeferred(task, nowMs, StoryFeedbackOutbox.BROADCAST_MIN_INTERVAL_MS)
            return null
        }
        // Себе автор сводку не шлёт (личный чат «Избранное» есть в контактах),
        // и не шлёт тем, кто свой отклик уже применил у себя.
        val echoes = StoryFeedbackStore.echoExclusions(task.storyId)
        val peers = StoryFeedbackOutbox.broadcastRecipients(chatRepository.directContactIds(), null)
            .filterNot { it == ownId || it in echoes }
        if (peers.isEmpty()) {
            StoryFeedbackStore.markBroadcastSent(task.storyId, System.currentTimeMillis())
            return true
        }
        var sent = 0
        for (peer in peers) {
            if (!swarmBudget.tryAcquire(SwarmLane.SIGNAL)) break
            if (chatRepository.sendStoryPacket(peer, outgoing.content, outgoing.transportId)) sent++
        }
        if (sent < peers.size) {
            if (task.attempts >= StoryFeedbackOutbox.MAX_BROADCAST_ATTEMPTS) {
                // Веер не сошёлся: версию отмечаем, иначе задача повторялась бы
                // сутки. Остаток зрителей доберёт сводку просьбой при открытии.
                Log.i(TAG, "story feedback fanout gave up: $sent/${peers.size}")
                StoryFeedbackStore.markBroadcastSent(task.storyId, System.currentTimeMillis())
                return true
            }
            // Версию НЕ отмечаем: иначе остаток зрителей сводку не получил бы
            // никогда. Повтор уходит тем же сообщением (транспортный
            // идентификатор зависит от версии), поэтому уже получившие отклик
            // узлы отбросят его как повтор.
            Log.i(TAG, "story feedback fanout incomplete: $sent/${peers.size} (signal budget)")
            return false
        }
        StoryFeedbackStore.markBroadcastSent(task.storyId, System.currentTimeMillis())
        return true
    }

    /** Толчок досылки сразу после действия человека. */
    private fun pumpNow() {
        scope.launch {
            try {
                pump()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "story feedback immediate pump failed: " + error.message)
            }
        }
    }

    private fun profileName(): String = UsernameHolder.name.value.orEmpty()

    private companion object {
        const val TAG = "StoryFeedback"
    }
}
