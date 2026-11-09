package com.vladimir.messenger.data.chatclear

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Durable clear requests. A queue entry is removed only by the expected authenticated peer. */
internal class ChatClearOutbox(private val file: File) {
    data class Entry(val owner: String, val peer: String, val chat: String, val batch: ChatClearWire.Batch)

    fun enqueue(entries: List<Entry>) = synchronized(lock) {
        val current = load().toMutableList()
        require(entries.all { it.owner.isNotBlank() && it.peer.isNotBlank() && it.chat.isNotBlank() && it.owner.length <= 512 && it.peer.length <= 512 && it.chat.length <= 512 })
        val all = current + entries
        require(all.size <= MAX_ENTRIES && all.map { it.batch.operation }.distinct().size == all.size)
        entries.forEach { ChatClearWire.encode(it.batch) }
        write(all)
    }

    fun acknowledge(authenticated: Boolean, owner: String, peer: String, operation: UUID): Boolean = synchronized(lock) {
        if (!authenticated || owner.isBlank() || peer.isBlank()) return@synchronized false
        val current = load()
        val matching = current.any { it.owner == owner && it.peer == peer && it.batch.operation == operation }
        if (matching) write(current.filterNot { it.owner == owner && it.peer == peer && it.batch.operation == operation })
        matching
    }

    fun pending(): List<Entry> = synchronized(lock) { load() }

    private fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        check(file.isFile && file.length() <= MAX_FILE_BYTES) { "Invalid clear outbox" }
        return DataInputStream(FileInputStream(file).buffered()).use { input ->
            check(input.readInt() == MAGIC) { "Invalid clear outbox header" }
            val count = input.readInt()
            check(count in 0..MAX_ENTRIES) { "Invalid clear outbox count" }
            val entries = List(count) {
                val owner = input.readUTF()
                val peer = input.readUTF()
                val chat = input.readUTF()
                val batch = ChatClearWire.decode(input.readUTF()) ?: error("Invalid clear outbox packet")
                check(owner.isNotBlank() && owner.length <= 512 && peer.isNotBlank() && peer.length <= 512 && chat.isNotBlank() && chat.length <= 512)
                Entry(owner, peer, chat, batch)
            }
            check(input.read() == -1 && entries.map { it.batch.operation }.distinct().size == entries.size)
            entries
        }
    }

    private fun write(entries: List<Entry>) {
        val directory = file.absoluteFile.parentFile ?: error("Missing outbox directory")
        check(directory.mkdirs() || directory.isDirectory)
        val temp = File(directory, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                val output = DataOutputStream(stream.buffered())
                output.writeInt(MAGIC)
                output.writeInt(entries.size)
                entries.forEach { output.writeUTF(it.owner); output.writeUTF(it.peer); output.writeUTF(it.chat); output.writeUTF(ChatClearWire.encode(it.batch)) }
                output.flush()
                stream.fd.sync()
            }
            check(temp.length() <= MAX_FILE_BYTES)
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // Same-directory replace is the fallback on filesystems without atomic move support.
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        private val lock = Any()
        private const val MAGIC = 0x41504332
        const val MAX_ENTRIES = 2048
        private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    }
}
