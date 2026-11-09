package com.vladimir.messenger.data.chatclear

import android.content.Context
import com.vladimir.messenger.data.file.FileTransferChunkStore
import com.vladimir.messenger.data.file.FileTransferKeyVault
import com.vladimir.messenger.data.file.FileTransferWire
import com.vladimir.messenger.data.file.ReceivedFileStore
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import java.io.File
import java.nio.file.Files

/** Deletes only application-private transfer data, never a user's exported file. */
internal class ChatClearPrivateFileCleaner(context: Context) {
    private val context = context.applicationContext
    private val chunks = FileTransferChunkStore.forApplication(this.context)
    private val received = ReceivedFileStore(File(this.context.noBackupFilesDir, "file_received/v1"))

    fun remove(transfer: FileTransferEntity): Boolean {
        val id = transfer.transferId
        FileTransferWire.requireValidTransferId(id)
        if (!chunks.deleteTransfer(id)) return false
        if (!received.deleteTransfer(id)) return false
        if (!FileTransferKeyVault.deleteKey(context, id)) return false
        val parent = File(context.noBackupFilesDir, "file_preview")
        val root = File(parent, "v1")
        check(!Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(root.toPath())) { "Symbolic preview root rejected" }
        check(root.canonicalFile.toPath().startsWith(context.noBackupFilesDir.canonicalFile.toPath())) { "Preview root escaped private storage" }
        for (suffix in listOf(".jpg", ".gif")) {
            val file = File(root, id + suffix)
            check(!Files.isSymbolicLink(file.toPath())) { "Symbolic preview rejected" }
            if (file.exists() && (!file.isFile || !file.delete())) return false
        }
        return true
    }
}
