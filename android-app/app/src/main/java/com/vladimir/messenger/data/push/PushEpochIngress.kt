package com.vladimir.messenger.data.push

import android.content.Context
import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * The narrow, authenticated exception to the push-control quarantine.
 *
 * Every receive path in `CoreServerService` throws away the reserved
 * `APUPUSH…` family, because a plaintext service packet must never reach a chat,
 * create a contact, mirror, ACK or run another handler. This object exists so
 * exactly one case can be handled instead of dropped: a *sealed* envelope that
 * opens under our own key, whose embedded sender binding is the pinned key of
 * the claimed sender, and whose plaintext is an epoch packet for this pair.
 *
 * It returns true only when the packet was recognised as an epoch packet — the
 * caller must then drop it from the chat path. Any doubt (unsealed, unknown
 * sender, failed open, unreadable journal) returns false, which keeps the old
 * quarantine behaviour.
 *
 * With the feature disabled in this build it returns false before reading a
 * journal, a pin or the database.
 */
internal object PushEpochIngress {

    suspend fun accept(context: Context, senderId: String, wire: String): Boolean {
        if (senderId.isBlank() || wire.isBlank()) return false
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return false
        return try {
            val importer = PushServiceLocator.shared(app).epochImport ?: return false
            val outcome = importer.import(senderId, wire)
            // Every outcome is recorded, because "the peer never offered" and
            // "the peer offered and we refused" look identical in a report that
            // only counts stored epochs.
            PushDiagnostics.noteEpochImport(
                applied = outcome == PushEpochImport.Outcome.APPLIED ||
                    outcome == PushEpochImport.Outcome.DUPLICATE,
                reason = when (outcome) {
                    PushEpochImport.Outcome.APPLIED -> "applied"
                    PushEpochImport.Outcome.DUPLICATE -> "duplicate"
                    PushEpochImport.Outcome.REJECTED -> "rejected"
                    PushEpochImport.Outcome.NOT_APPLICABLE -> "not_applicable"
                    PushEpochImport.Outcome.UNAVAILABLE -> "unavailable"
                },
            )
            outcome == PushEpochImport.Outcome.APPLIED || outcome == PushEpochImport.Outcome.DUPLICATE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the packet quarantined; never log the sender or the packet.
            Logger.getLogger("PushEpochIngress").warning("Epoch ingress failed")
            false
        }
    }
}
