package com.vladimir.messenger.data.group

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.push.PushDiagnostics
import com.vladimir.messenger.data.push.PushEpochPublish
import com.vladimir.messenger.data.push.PushServiceLocator
import com.vladimir.messenger.data.push.PushWakePublish
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Detached, bounded executor for pairwise push wakes and key/epoch bootstrap
 * after group and channel fanouts.
 *
 * Never blocks message delivery, never alters message status, never logs peer
 * or group identifiers, and returns immediately when the remote-push gate is
 * closed.
 */
internal object GroupPushWake {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeSemaphore = Semaphore(GroupPushWakePolicy.MAX_CONCURRENT_WAKES)
    private val pendingRetries = ConcurrentHashMap<String, Job>()

    /** Invoked after [PerMemberFanoutDelivery.deliver] completes on a group/channel packet. */
    fun onFanoutComplete(
        context: Context,
        envelope: String,
        orderedRecipients: List<String>,
        report: DeliveryReport,
        wakeOnUnreachable: Boolean,
        fileRouter: () -> FileTransferRouter?,
    ) {
        if (orderedRecipients.isEmpty()) return
        val app = context.applicationContext
        val pushEnabled = PushServiceLocator.enabled(app)
        if (!pushEnabled) return
        if (!GroupPushWakePolicy.isSharedGroupCorrespondentPacket(envelope)) return
        scope.launch {
            try {
                val plan = GroupPushWakePolicy.planFanout(
                    ownNodeId = RustBridge.nodeId(),
                    allRecipients = orderedRecipients,
                    failedRecipients = report.failed,
                    wakeRequested = wakeOnUnreachable,
                    envelope = envelope,
                ) { peerId -> mapReadiness(PushWakePublish.peerReadiness(app, peerId)) }
                if (plan.isEmpty) return@launch

                // 1. Kick off missing-pin durable `File HELLO`s so unpinned group/channel
                //    members exchange X25519 keys, register push routes and share epochs.
                if (plan.helloPeers.isNotEmpty()) {
                    val router = runCatching { fileRouter() }.getOrNull()
                    if (router != null) {
                        for (peerId in plan.helloPeers) {
                            runCatching { router.requestHelloIfUnpinned(peerId) }
                                .onFailure { diag("Group HELLO bootstrap deferred") }
                        }
                    }
                }

                // 2. Wake unreachable epoch-ready members first, then ask a small batch of
                //    pinned-without-epoch members for their epoch (concurrency capped at 4).
                val wakeTargets = plan.wakePeers + plan.requestEpochPeers
                if (wakeTargets.isNotEmpty()) {
                    coroutineScope {
                        wakeTargets.map { peerId ->
                            async {
                                wakeSemaphore.withPermit {
                                    wakePeerOnce(app, peerId, allowRetry = peerId in plan.wakePeers)
                                }
                            }
                        }.awaitAll()
                    }
                }

                // 3. Offer our own epoch (15-min per-peer throttle inside PushEpochPublish)
                //    so silent listeners/subscribers can wake us too.
                for (peerId in plan.announcePeers) {
                    PushEpochPublish.noteCorrespondent(app, peerId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                diag("Group push fanout skipped")
            }
        }
    }

    /** Single-peer wake for scheduled group/channel fallback after its deadline. */
    fun wakeScheduledFallback(context: Context, peerId: String, isGroup: Boolean) {
        if (peerId.isBlank()) return
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return
        scope.launch {
            try {
                wakeSemaphore.withPermit {
                    if (isGroup) {
                        wakePeerOnce(app, peerId, allowRetry = true)
                    } else {
                        val outcome = PushWakePublish.wake(app, peerId)
                        if (outcome == PushWakePublish.Outcome.Unavailable) {
                            scheduleSingleRetry(app, peerId, isGroup = false)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                diag("Scheduled fallback wake skipped")
            }
        }
    }

    /**
     * Post-handling hook for inbound group/channel packets: refreshes presence
     * audience after roster/admission commits and offers our epoch (15-min
     * throttled) only to peers of groups/channels we genuinely belong to.
     */
    suspend fun onSharedIncoming(
        context: Context?,
        groupDao: com.vladimir.messenger.data.local.dao.GroupDao,
        ownNodeId: String,
        senderId: String,
        packet: GroupWire.Packet,
    ) {
        val app = context?.applicationContext ?: return
        if (ownNodeId.isBlank() || senderId == ownNodeId) return
        val groupId = GroupPushWakePolicy.sharedGroupId(packet) ?: return
        val member = runCatching { groupDao.getMember(groupId, ownNodeId) }.getOrNull()
        if (member == null || member.isBanned) return
        if (packet is GroupWire.Packet.Roster ||
            (packet is GroupWire.Packet.JoinDecision && packet.approved && packet.nodeId == ownNodeId)
        ) {
            com.vladimir.messenger.data.push.PushPeerBootstrap.scheduleAudienceRefresh(
                app,
                triggerHelloSweep = true,
            )
        }
        if (!PushServiceLocator.enabled(app)) return
        // Exclude arbitrary unapproved join requests from strangers; only real
        // fellow members (or our group's owner) learn our epoch automatically.
        val group = runCatching { groupDao.getGroupById(groupId) }.getOrNull() ?: return
        val senderMember = runCatching { groupDao.getMember(groupId, senderId) }.getOrNull()
        val trustedCorrespondent = senderId == group.ownerId ||
            (senderMember != null && !senderMember.isBanned)
        if (!trustedCorrespondent) return
        com.vladimir.messenger.service.announceGroupCorrespondent(app, senderId)
    }

    /** Matches 1:1 `IncomingMessageStore` durability for committed group/channel alerts. */
    fun onMessageCommitted(context: Context?, notificationPending: Boolean) {
        if (!notificationPending || context == null) return
        runCatching {
            com.vladimir.messenger.worker.IncomingNotificationRecoveryWorker.enqueue(context.applicationContext)
        }.onFailure { diag("Group notification recovery enqueue skipped") }
    }

    private suspend fun wakePeerOnce(app: Context, peerId: String, allowRetry: Boolean) {
        val outcome = PushWakePublish.wake(app, peerId)
        when (outcome) {
            PushWakePublish.Outcome.Accepted -> {
                pendingRetries.remove(peerId)?.cancel()
                PushDiagnostics.noteGroupWake(accepted = true)
            }
            PushWakePublish.Outcome.NotRegistered,
            PushWakePublish.Outcome.NoPeerEpoch -> {
                PushDiagnostics.noteGroupWake(accepted = false)
            }
            PushWakePublish.Outcome.Unavailable -> {
                PushDiagnostics.noteGroupWake(accepted = false)
                if (allowRetry) scheduleSingleRetry(app, peerId, isGroup = true)
            }
            // Local throttle/budget hits sent no request; keep group attempt count honest.
            PushWakePublish.Outcome.WithinBudget,
            PushWakePublish.Outcome.Skipped -> Unit
        }
    }

    private fun scheduleSingleRetry(app: Context, peerId: String, isGroup: Boolean) {
        val existing = pendingRetries[peerId]
        if (existing != null && existing.isActive) return
        val job = scope.launch {
            try {
                delay(GroupPushWakePolicy.UNAVAILABLE_RETRY_DELAY_MS)
                wakeSemaphore.withPermit {
                    val outcome = PushWakePublish.wake(app, peerId)
                    if (isGroup && outcome != PushWakePublish.Outcome.WithinBudget &&
                        outcome != PushWakePublish.Outcome.Skipped
                    ) {
                        PushDiagnostics.noteGroupWake(accepted = outcome == PushWakePublish.Outcome.Accepted)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                diag("Group push retry skipped")
            } finally {
                pendingRetries.remove(peerId)
            }
        }
        pendingRetries[peerId] = job
    }

    private fun mapReadiness(readiness: PushWakePublish.PeerReadiness): GroupPushWakePolicy.CandidateState =
        when (readiness) {
            PushWakePublish.PeerReadiness.EPOCH_READY -> GroupPushWakePolicy.CandidateState.EPOCH_READY
            PushWakePublish.PeerReadiness.PINNED_NO_EPOCH -> GroupPushWakePolicy.CandidateState.PINNED_NO_EPOCH
            PushWakePublish.PeerReadiness.EPOCH_COOLDOWN -> GroupPushWakePolicy.CandidateState.EPOCH_COOLDOWN
            PushWakePublish.PeerReadiness.UNPINNED_OR_NOT_READY -> GroupPushWakePolicy.CandidateState.UNPINNED
        }

    private fun diag(message: String) {
        Logger.getLogger("GroupPushWake").fine(message)
    }
}
