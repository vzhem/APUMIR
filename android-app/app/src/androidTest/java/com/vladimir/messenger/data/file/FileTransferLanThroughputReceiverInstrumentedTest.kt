package com.vladimir.messenger.data.file

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.decryptFileTransferChunk
import uniffi.p2p_core.parseFileTransferManifest

/**
 * Direct phone-to-phone LAN throughput receiver ("phone as server").
 *
 * Listens on 0.0.0.0:server_port, receives ONE manifest frame followed by
 * chunkCount encrypted chunk frames, decrypts each chunk (bounded memory),
 * streams the plaintext to disk while hashing it, compares the full-file
 * SHA-256 with the manifest, and reports throughput over the socket.
 */
@RunWith(AndroidJUnit4::class)
class FileTransferLanThroughputReceiverInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun receivesLargeFileDirectlyFromSenderPhone() {
        val arguments = InstrumentationRegistry.getArguments()
        val port = arguments.getString("server_port")?.toIntOrNull() ?: error("Missing server_port")
        val key = decode(arguments.getString("file_test_key") ?: error("Missing test key"))
        val expectedSender = arguments.getString("expected_sender") ?: error("Missing expected_sender")
        val expectedBytes = arguments.getString("expected_bytes")?.toLongOrNull() ?: error("Missing expected_bytes")
        val runId = arguments.getString("file_run_id") ?: "run"
        require(port in 1024..65535 && key.size == 32)
        require(expectedBytes in 1..2_000_000_000L)

        val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val receiver = prefs.getString("existing_public_key", null)
            ?: prefs.getString("node_id", null)
            ?: error("Missing receiver identity")

        val targetDir = context.noBackupFilesDir
        if (targetDir.usableSpace < expectedBytes + 64L * 1024 * 1024) {
            error("Not enough free space for $expectedBytes bytes")
        }
        val outFile = File(targetDir, "lan-throughput-$runId.bin")
        assertTrue(!outFile.exists())

        ServerSocket(port, 4).use { server ->
            server.soTimeout = 120_000
            System.out.println("LAN-RX listening on all interfaces port $port")
            server.accept().use { socket ->
                socket.soTimeout = 300_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val manifestLength = input.readInt()
                require(manifestLength in 1..1_048_576)
                val manifestBytes = ByteArray(manifestLength)
                input.readFully(manifestBytes)
                val manifest = parseFileTransferManifest(manifestBytes)
                assertEquals(expectedSender, manifest.senderNodeId)
                assertEquals(receiver, manifest.recipientNodeId)
                assertEquals(expectedBytes, manifest.fileSize.toLong())
                val chunkSize = manifest.chunkSize.toLong()
                val chunkCount = manifest.chunkCount.toLong()
                System.out.println(
                    "LAN-RX manifest ok: chunkSize=$chunkSize chunkCount=$chunkCount totalBytes=${manifest.fileSize.toLong()}",
                )

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                val startedAt = System.nanoTime()
                val progressStep = (chunkCount / 10).coerceAtLeast(1L)
                FileOutputStream(outFile).use { fileOut ->
                    for (index in 0L until chunkCount) {
                        val frameIndex = input.readLong()
                        assertEquals(index, frameIndex)
                        val length = input.readInt()
                        require(length in 1..(chunkSize + 1024).toInt())
                        val ciphertext = ByteArray(length)
                        input.readFully(ciphertext)
                        val plaintext = decryptFileTransferChunk(manifestBytes, key, index.toULong(), ciphertext)
                        ciphertext.fill(0)
                        digest.update(plaintext)
                        fileOut.write(plaintext)
                        total += plaintext.size.toLong()
                        plaintext.fill(0)
                        if (index % progressStep == 0L) {
                            System.out.println("LAN-RX progress: chunk ${index + 1}/$chunkCount ($total bytes)")
                        }
                    }
                    fileOut.flush()
                }

                val shaHex = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                assertEquals(manifest.fileSha256Hex.lowercase(), shaHex)
                assertEquals(expectedBytes, total)

                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                val mbps = total * 8.0 / 1_000_000_000.0 / elapsedSeconds
                val verdict = "OK bytes=$total sha256=$shaHex seconds=" +
                    "%.3f".format(elapsedSeconds) + " mbps=" + "%.2f".format(mbps) +
                    " chunks=$chunkCount chunkSize=$chunkSize"
                System.out.println("LAN-RX RESULT: $verdict")

                val verdictBytes = verdict.toByteArray(Charsets.UTF_8)
                output.writeInt(verdictBytes.size)
                output.write(verdictBytes)
                output.flush()
            }
        }

        assertTrue(outFile.delete())
        assertTrue(!outFile.exists())
        key.fill(0)
    }

    private fun decode(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
}
