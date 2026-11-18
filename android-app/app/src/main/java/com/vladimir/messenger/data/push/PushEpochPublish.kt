package com.vladimir.messenger.data.push

import android.content.Context
import java.util.concurrent.CancellationException
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The single call site through which ordinary sending announces this device's
 * own epoch to a correspondent.
 *
 * Push is a *second* path, not a replacement: the message has already been
 * written and handed to the existing transport when this runs, so every outcome
 * here is best effort. Nothing in this file changes a message status, retries a
 * send, or reports delivery — it only makes the reply path possible.
 *
 * While the gate is closed (every shipped build) it returns immediately, without
 * touching a journal, a database or the network.
 */
internal object PushEpochPublish {

    /**
     * How often this phone re-offers its epoch to one correspondent.
     *
     * An offer is one small frame, but every ordinary send would otherwise add
     * one: a live conversation would put dozens of useless copies into the relay
     * queue that is already full on the phones that need this most. Five minutes
     * is short enough that a peer who missed the last offer learns the epoch in
     * the same conversation.
     *
     * Владелец 2026-11-18: «у нас точно не копятся ненужные строчки отправок?»
     * Каждое объявление — отдельная строка в очереди и в durable-сторе, поэтому
     * с 5 минут стало 15: собеседник, который был в сети, получает адрес в тот
     * же вечер, а не в тот же час, и рост строк падает втрое. Ядро теперь ещё и
     * склеивает повторы (`coalesce_key`), так что старая копия не занимает
     * места рядом с новой.
     */
    private const val OFFER_INTERVAL_MS = 15 * 60_000L

    /** id-подсказка ядру: анонс эпохи — пробуждение и адрес (высший класс). */
    private const val PUSH_WAKE_ID_PREFIX = "push1-"

    /** Bounded per-pass quotas so large channels never flood the durable relay queue. */
    private const val MAX_UNANNOUNCED_OFFERS_PER_PASS = 48
    private const val MAX_REFRESH_OFFERS_PER_PASS = 16

    /** Process-lifetime throttle per peer; a restart may re-offer once. */
    private val lastOfferAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Shared background scope for non-blocking correspondent announcements. */
    private val publishScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Offers our current registered epoch to [peerId] if, and only if, this peer
     * has a live pin and an accepted registration. Otherwise it does nothing: a
     * pin is never created here, and an unregistered epoch cannot be offered.
     */
    suspend fun noteCorrespondent(context: Context, peerId: String) {
        if (peerId.isBlank()) return
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return
        val now = System.currentTimeMillis()
        val previous = lastOfferAtMs[peerId]
        if (previous != null && now >= previous && now - previous < OFFER_INTERVAL_MS) return
        lastOfferAtMs[peerId] = now
        try {
            val shared = PushServiceLocator.shared(app)
            shared.resolver.resolveForRegistration(peerId)?.use { pass ->
                val state = pass.registration ?: return@use
                if (state.phase != PushRegistrationState.Phase.ACTIVE || state.registeredAtMs <= 0) return@use
                val mailbox = shared.epochs?.let { delivery ->
                    delivery.offer(
                        state, peerId,
                        pass.localBinding, pass.pinnedPeerBinding, pass.localSecret,
                    )
                }
                val byMailbox = mailbox == PushEpochDelivery.Outcome.Handled ||
                    mailbox == PushEpochDelivery.Outcome.Stored
                // The mailbox is the second path, not the only one. While it was
                // the only one, a phone whose mailbox was unavailable could never
                // learn a peer's epoch - and an epoch is the whole address of a
                // wake. The ordinary transport already has a sealed service-text
                // receive path that consumes exactly this packet before any chat.
                val byMessage = !byMailbox && announceOverTransport(app, peerId, state)
                if (byMailbox) {
                    // A server answer to our own write is proof the endpoint is
                    // reachable, and it is fresher than the startup probe that
                    // kept reading as an outage in the report.
                    PushDiagnostics.noteEndpoint(true)
                }
                if (byMailbox || byMessage) {
                    shared.journals.registrations.announced(
                        PushRegistrationStore.Participants(
                            pass.localId, pass.peerId, pass.localBinding, pass.pinnedPeerBinding,
                        ),
                        state.generation,
                    )
                }
                PushDiagnostics.noteEpochOffer(
                    overMessage = byMessage,
                    overMailbox = byMailbox,
                    blockedReason = if (byMailbox || byMessage) null else offerReason(mailbox),
                )
                if (!byMailbox && !byMessage) diag("Epoch offer not accepted")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the peer, the epoch or exception text.
            diag("Epoch offer failed")
        }
    }

    /**
     * Offers the epoch inside a sealed service text over the ordinary transport.
     *
     * The receiving phone opens the envelope, recognises the reserved
     * `APUTYP1|APUPUSH1|` family and stores the epoch before the text can reach a
     * chat, so this path adds no chat row, no notification and no ACK. It exists
     * because an epoch is only an address hint: without it the other phone has
     * nothing to wake us *with*, regardless of how healthy the mailbox is.
     */
    private suspend fun announceOverTransport(
        app: android.content.Context,
        peerId: String,
        state: PushRegistrationState.State,
    ): Boolean {
        val text = state.announcement() ?: return false
        // The ordinary message path is the only one that reaches a phone that is
        // asleep, offline or simply not directly connected: the core keeps the
        // frame in its relay queue and delivers it when the peer appears. A direct
        // payload needs a live channel - exactly what a sleeping phone does not
        // have - so it is only the last resort.
        //
        // The text goes in UNSEALED: `RustBridge.sendMessage` seals it itself and
        // a second envelope would reach the peer as sealed-sealed, fail the
        // reserved-family check and land in the chat as a broken message.
        // Group and channel members rarely share a 1:1 chat row, yet they still
        // need durable relay custody for sealed `APUPUSH1` offers when offline.
        // The wire `chat_id` slot is opaque to the core and quarantined before
        // any chat lookup on the receiver, so [peerId] (like `File HELLO`) is a
        // safe pipe-free fallback whenever no direct chat exists.
        val effectiveChatId = chatFor(app, peerId)
            ?: peerId.takeIf { com.vladimir.messenger.util.NodeIds.isNodeId(it) }
        if (effectiveChatId != null) {
            val accepted = try {
                com.vladimir.messenger.data.RustBridge.sendMessage(
                    // Подсказка ядру: это пробуждение и адрес, самый важный
                    // класс трафика (см. `rust-core/src/network/traffic_priority.rs`).
                    // Анонс запечатан, по телу его не отличить, поэтому класс
                    // передаётся в id; получателю и ACK id безразличен.
                    PUSH_WAKE_ID_PREFIX + java.util.UUID.randomUUID(),
                    effectiveChatId,
                    peerId,
                    text,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                diag("Epoch transport announce failed")
                false
            }
            // `false` here means "not delivered by a direct channel", not "lost":
            // the core still owns the frame and relays it. Counting it as a
            // failure would send a second copy over the direct path every time.
            if (!accepted) diag("Epoch announce queued for relay")
            return true
        }
        return try {
            com.vladimir.messenger.data.RustBridge.sendDirectPayload(peerId, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the peer, the epoch or exception text.
            diag("Epoch transport announce failed")
            false
        }
    }

    /** The direct chat with [peerId], or null when this pair has never talked. */
    private suspend fun chatFor(app: android.content.Context, peerId: String): String? = try {
        PushServiceLocator.shared(app).database.chatDao().getChatByContactId(peerId)?.id
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Никакой строки чата — значит обычный путь недоступен, и останется
        // только прямой кадр. Считаем это отдельно: иначе «эпоха не дошла»
        // выглядит одинаково и при отсутствии чата, и при мёртвом канале.
        PushDiagnostics.noteEpochNoChat()
        diag("Epoch announce chat lookup failed")
        null
    }

    /** Fixed name for the report; never a server response and never a host. */
    private fun offerReason(outcome: PushEpochDelivery.Outcome?): String = when (outcome) {
        null -> "no_mailbox"
        PushEpochDelivery.Outcome.Deferred -> "deferred"
        PushEpochDelivery.Outcome.NotReady -> "not_ready"
        PushEpochDelivery.Outcome.Throttled -> "throttled"
        PushEpochDelivery.Outcome.Failed -> "failed"
        else -> "no_text"
    }

    /**
     * After a successful (re)registration, tell every peer that has a live pin
     * about the epoch we just registered. A rotation is useless until the peer
     * learns the new one; without this a stale epoch would be silently used.
     *
     * A peer whose epoch we do not have yet will refuse the mailbox write, which
     * is reported as deferred, not as delivered.
     */
    suspend fun noteAllRegistered(context: Context) {
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return
        try {
            val (outcome, peers) = PushServiceLocator.targets(app)()
            if (outcome != PushTargets.Outcome.COMPLETED) return
            // Newly registered or rotated peers (`announcedGeneration < generation`)
            // always go first so group/channel members learn our address right away,
            // while periodic startup refreshes stay bounded on large rosters.
            val unannounced = ArrayList<String>()
            val announced = ArrayList<String>()
            val shared = PushServiceLocator.shared(app)
            for (peer in peers) {
                if (peer.peerId.isBlank()) continue
                val needsFirstOffer = shared.resolver.resolveForRegistration(peer.peerId)?.use { pass ->
                    val state = pass.registration
                    state != null &&
                        state.phase == PushRegistrationState.Phase.ACTIVE &&
                        state.registeredAtMs > 0 &&
                        state.announcedGeneration < state.generation
                } ?: false
                if (needsFirstOffer) unannounced.add(peer.peerId) else announced.add(peer.peerId)
            }
            for (peerId in unannounced.take(MAX_UNANNOUNCED_OFFERS_PER_PASS)) {
                noteCorrespondent(app, peerId)
            }
            for (peerId in announced.take(MAX_REFRESH_OFFERS_PER_PASS)) {
                noteCorrespondent(app, peerId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag("Epoch announcement pass failed")
        }
    }

    /**
     * Tells every live peer that this epoch must no longer be used, after the
     * route was revoked. A peer that keeps the old offer would keep writing into
     * a mailbox whose row the server just removed, so the revoke has to travel
     * the same way the offer did.
     *
     * A failed revoke announcement is not a failed local revocation: the local
     * index is already cleared and the server operation already happened.
     */
    suspend fun noteRevoked(context: Context, peerId: String) {
        if (peerId.isBlank()) return
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return
        try {
            val shared = PushServiceLocator.shared(app)
            val delivery = shared.epochs ?: return
            shared.resolver.resolveForRegistration(peerId)?.use { pass ->
                val state = pass.registration ?: return@use
                if (state.phase != PushRegistrationState.Phase.REVOKING &&
                    state.phase != PushRegistrationState.Phase.REVOKED
                ) {
                    diag("Epoch revoke skipped: the record is not revoking")
                    return@use
                }
                when (delivery.revoke(state, peerId, pass.localBinding, pass.pinnedPeerBinding, pass.localSecret)) {
                    PushEpochDelivery.Outcome.Handled,
                    PushEpochDelivery.Outcome.Stored -> Unit
                    else -> diag("Epoch revoke not accepted")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag("Epoch revoke failed")
        }
    }

    /**
     * Non-suspending variant for transport wrappers: it only starts the offer on
     * a background scope when the gate is open, so a caller on a hot path never
     * waits for the network and never sees its failure.
     */
    fun noteCorrespondentIfEnabled(context: Context, peerId: String) {
        if (peerId.isBlank()) return
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return
        publishScope.launch {
            noteCorrespondent(app, peerId)
        }
    }

    /**
     * Просим собеседника прислать эпоху — тем же кадром, что и обычное
     * объявление, но вне пятиминутного расписания.
     *
     * Зачем отдельный вход. Пока эпохи нет, пробуждение некуда отправить, и
     * каждые 15 секунд повторялась одна и та же пустая попытка. Вместо этого
     * телефон один раз просит (наша эпоха + новая сборка собеседника отвечает
     * своей), а повторные попытки уходят в редкий фон.
     */
    suspend fun requestEpoch(context: Context, peerId: String) {
        if (peerId.isBlank()) return
        lastOfferAtMs.remove(peerId)
        noteCorrespondent(context, peerId)
    }

    private fun diag(message: String) {
        Logger.getLogger("PushEpochPublish").fine(message)
    }
}
