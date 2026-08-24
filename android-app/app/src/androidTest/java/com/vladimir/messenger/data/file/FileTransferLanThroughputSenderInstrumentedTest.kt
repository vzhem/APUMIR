package com.vladimir.messenger.data.file

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.createFileTransferManifest
import uniffi.p2p_core.encryptFileTransferChunk

/**
 * Direct phone-to-phone LAN throughput sender.
 *
 * Streams a deterministic plaintext of `file_bytes` bytes to the receiver phone
 * over ONE TCP connection: manifest frame first, then chunkCount AEAD-encrypted
 * chunk frames ([u64 index][u32 length][ciphertext]). The plaintext never exists
 * in memory in full: it is generated, hashed, encrypted and sent chunk by chunk.
 * The generator is seeded by ABSOLUTE OFFSET, so any chunk geometry produces the
 * same byte stream (hash pass and send pass cannot diverge).
 */
@RunWith(AndroidJUnit4::class)
class FileTransferLanThroughputSenderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun streamsLargeFileDirectlyToReceiverPhone() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("tcp_host") ?: error("Missing tcp_host")
        val port = arguments.getString("tcp_port")?.toIntOrNull() ?: error("Missing tcp_port")
        val recipient = arguments.getString("recipient_node") ?: error("Missing recipient_node")
        val key = decode(arguments.getString("file_test_key") ?: error("Missing test key"))
        val totalBytes = arguments.getString("file_bytes")?.toLongOrNull() ?: error("Missing file_bytes")
        require(host.isNotBlank() && port in 1024..65535 && key.size == 32)
        require(totalBytes in 1..2_000_000_000L)

        val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val sender = prefs.getString("existing_public_key", null)
            ?: prefs.getString("node_id", null)
            ?: error("Missing sender identity")
        val displayName = prefs.getString("display_name", "Sender") ?: "Sender"

        val now = System.currentTimeMillis()
        val manifest = createFileTransferManifest(
            sender,
            recipient,
            displayName,
            "application/octet-stream",
            totalBytes.toULong(),
            hashGeneratedFile(totalBytes),
            now,
            now + 24L * 60L * 60L * 1_000L,
        )
        val chunkSize = manifest.chunkSize.toLong().coerceAtLeast(1L)
        val chunkCount = manifest.chunkCount.toLong()
        assertEquals((totalBytes + chunkSize - 1) / chunkSize, chunkCount)
        System.out.println("LAN-TX manifest: chunkSize=$chunkSize chunkCount=$chunkCount totalBytes=$totalBytes")

        val startedAt = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 20_000)
            socket.soTimeout = 300_000
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            output.writeInt(manifest.manifestBytes.size)
            output.write(manifest.manifestBytes)
            output.flush()

            val progressStep = (chunkCount / 10).coerceAtLeast(1L)
            var offset = 0L
            for (index in 0L until chunkCount) {
                val length = (totalBytes - offset).coerceAtMost(chunkSize)
                val plaintext = ByteArray(length.toInt())
                Random(offset).nextBytes(plaintext)
                val ciphertext = encryptFileTransferChunk(manifest.manifestBytes, key, index.toULong(), plaintext)
                output.writeLong(index)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
                output.flush()
                plaintext.fill(0)
                offset += length
                if (index % progressStep == 0L) {
                    System.out.println("LAN-TX progress: chunk ${index + 1}/$chunkCount ($offset bytes)")
                }
            }

            val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
            val mbps = totalBytes * 8.0 / 1_000_000.0 / elapsedSeconds
            System.out.println(
                "LAN-TX sent all chunks: bytes=$totalBytes seconds=" +
                    "%.3f".format(elapsedSeconds) + " throughput-mbps=" + "%.2f".format(mbps),
            )

            val verdictLength = input.readInt()
            require(verdictLength in 1..1_048_576)
            val verdictBytes = ByteArray(verdictLength)
            input.readFully(verdictBytes)
            val verdict = verdictBytes.toString(Charsets.UTF_8)
            System.out.println("LAN-TX VERDICT: $verdict")
            assertTrue(verdict.startsWith("OK"))
            assertTrue(verdict.contains(manifest.fileSha256Hex))
            assertTrue(verdict.contains("bytes=$totalBytes"))
        }
        key.fill(0)
    }

    private fun hashGeneratedFile(totalBytes: Long): ByteArray {
        // Local hashing geometry does not need to match the manifest geometry:
        // the generator is seeded by absolute offset, so the concatenated
        // plaintext stream is identical in both passes.
        val digest = MessageDigest.getInstance("SHA-256")
        val hashChunk = 4L * 1024 * 1024
        var offset = 0L
        while (offset < totalBytes) {
            val length = (totalBytes - offset).coerceAtMost(hashChunk)
            val plaintext = ByteArray(length.toInt())
            Random(offset).nextBytes(plaintext)
            digest.update(plaintext)
            plaintext.fill(0)
            offset += length
        }
        return digest.digest()
    }

    private fun decode(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
}
