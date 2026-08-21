package com.vladimir.messenger.data.file

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * App-private area for verified received plaintext files. A file becomes visible under its final
 * name only after the caller finishes streaming and calls [Writer.commit]; a failed verification
 * must call [Writer.abort] so no unverified plaintext lingers.
 */
class ReceivedFileStore(private val root: File) {

    init {
        check(!root.exists() || root.isDirectory) { "Received file root is not a directory" }
    }

    interface Writer {
        fun write(bytes: ByteArray, length: Int)
        fun commit(): File
        fun abort()
    }

    fun openWriter(transferIdHex: String, displayName: String, expectedBytes: Long): Writer {
        FileTransferWire.requireValidTransferId(transferIdHex)
        require(expectedBytes >= 0)
        val directory = checkedChild(rootDirectory(create = true), transferIdHex)
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create received file directory" }
        val target = checkedChild(directory, sanitize(displayName))
        val temporary = checkedChild(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        return object : Writer {
            private val output = FileOutputStream(temporary)
            private var written = 0L
            private var finished = false

            override fun write(bytes: ByteArray, length: Int) {
                check(!finished) { "Received file writer already finished" }
                check(length in 1..bytes.size) { "Invalid received chunk length" }
                written = Math.addExact(written, length.toLong())
                check(written <= expectedBytes) { "Received file exceeds expected size" }
                output.write(bytes, 0, length)
            }

            override fun commit(): File {
                check(!finished) { "Received file writer already finished" }
                finished = true
                output.flush()
                output.fd.sync()
                output.close()
                check(temporary.length() == written) { "Incomplete received file write" }
                check(written == expectedBytes) { "Received file size mismatch" }
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                    throw IllegalStateException("Atomic received file move unavailable", unsupported)
                }
                return target
            }

            override fun abort() {
                if (finished) return
                finished = true
                runCatching { output.close() }
                temporary.delete()
            }
        }
    }

    fun deleteTransfer(transferIdHex: String): Boolean {
        FileTransferWire.requireValidTransferId(transferIdHex)
        val directory = checkedChild(rootDirectory(create = false), transferIdHex)
        if (!directory.exists()) return true
        check(!Files.isSymbolicLink(directory.toPath())) { "Symbolic received directory rejected" }
        directory.walkBottomUp().forEach { entry ->
            check(!Files.isSymbolicLink(entry.toPath())) { "Symbolic received entry rejected" }
            if (!entry.delete() && entry.exists()) return false
        }
        return !directory.exists()
    }

    fun receivedFile(transferIdHex: String, displayName: String): File? {
        FileTransferWire.requireValidTransferId(transferIdHex)
        val target = checkedChild(checkedChild(rootDirectory(create = false), transferIdHex), sanitize(displayName))
        return target.takeIf { it.isFile }
    }

    private fun rootDirectory(create: Boolean): File {
        if (create) check(root.mkdirs() || root.isDirectory) { "Cannot create received file root" }
        return root
    }

    private fun checkedChild(parent: File, name: String): File {
        val child = File(parent, name)
        check(child.canonicalFile.toPath().parent == parent.canonicalFile.toPath()) {
            "Received file path escaped its parent"
        }
        check(!Files.isSymbolicLink(child.toPath())) { "Symbolic received path rejected" }
        return child
    }

    internal fun sanitize(displayName: String): String {
        val cleaned = displayName.map { character ->
            if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
                character == '.' || character == '-' || character == '_'
            ) character else '_'
        }.joinToString("").trimEnd('.').take(MAX_NAME_CHARS)
        if (cleaned.isBlank() || cleaned == "." ) return "received_file"
        if (cleaned.startsWith('.')) return "f$cleaned"
        return cleaned
    }

    companion object {
        const val MAX_NAME_CHARS = 120
    }
}
