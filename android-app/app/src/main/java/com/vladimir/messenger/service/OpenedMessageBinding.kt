package com.vladimir.messenger.service

import android.util.Log
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import com.vladimir.messenger.data.file.FileTransferRouter

/**
 * Learn the signed binding of an opened ordinary message before its ACK, so a
 * receiver that missed HELLO can send a sealed reply. A changed pin is never
 * overwritten. Push controls must be quarantined before this TOFU operation.
 */
internal suspend fun rememberOpenedMessageBinding(router: FileTransferRouter, senderId: String, wire: String) {
    when (router.rememberOpenedMessageBinding(senderId, wire)) {
        FileTransferRouter.OpenedMessageBindingResult.KEY_CHANGED -> {
            TransferDiagnostics.notePeerKeyChanged()
            Log.w("CoreServerService", "opened message binding differs from pinned peer binding")
        }
        FileTransferRouter.OpenedMessageBindingResult.PINNED_NEW -> {
            Log.i("CoreServerService", "learned sender binding from opened message")
        }
        else -> Unit
    }
}
