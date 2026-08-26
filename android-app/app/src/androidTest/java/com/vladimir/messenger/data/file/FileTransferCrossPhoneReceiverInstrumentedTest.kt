package com.vladimir.messenger.data.file

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.data.RustBridge
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.FileTransferManifestFfi
import uniffi.p2p_core.decryptFileTransferChunk
import uniffi.p2p_core.parseFileTransferManifest

@RunWith(AndroidJUnit4::class)
class FileTransferCrossPhoneReceiverInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun receivesEncryptedFileAndPhotoFromOtherPhone() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("file_run_id") ?: error("Missing run ID")
        val expectedSender = arguments.getString("expected_sender") ?: error("Missing sender")
        val key = decode(arguments.getString("file_test_key") ?: error("Missing test key"))
        val serverPort = arguments.getString("server_port")?.toIntOrNull()
        require(runId.matches(Regex("^[0-9a-f]{16}$")) && key.size == 32)
        require(serverPort == null || serverPort in 1024..65535)
        val root = File(context.noBackupFilesDir, "file-cross-phone-receiver-test-v1")
        val ready = File(context.noBackupFilesDir, "file-cross-phone-receiver-ready-v1")
        root.deleteRecursively()
        ready.delete()
        val store = FileTransferChunkStore(root, maxStoreBytes = 2L * 1024 * 1024)
        val manifests = mutableMapOf<String, FileTransferManifestFfi>()
        val received = mutableMapOf<String, ByteArray>()
        var mqttEngineStarted = false
        try {
            val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            val publicKey = prefs.getString("existing_public_key", null)
                ?: prefs.getString("node_id", null)
                ?: error("Missing receiver identity")
            val receiver = if (serverPort != null) {
                publicKey
            } else {
                val privateKey = prefs.getString("existing_private_key", null) ?: publicKey
                val displayName = prefs.getString("display_name", "Receiver") ?: "Receiver"
                assertTrue(RustBridge.initialize(displayName, publicKey, privateKey, null))
                mqttEngineStarted = true
                RustBridge.nodeId() ?: error("Receiver engine has no node ID")
            }
            if (serverPort != null) {
                receiveDirectTcp(
                    serverPort,
                    ready,
                    runId,
                    expectedSender,
                    receiver,
                    key,
                    store,
                    manifests,
                    received,
                )
            } else {
                ready.writeText("ready")
                receiveMqtt(runId, expectedSender, receiver, key, store, manifests, received)
            }
            assertEquals(setOf("file", "photo"), received.keys)
            assertArrayEquals(expectedFileBytes(), received["file"])
            val photo = received["photo"]!!
            assertTrue(photo.size > 100)
            assertArrayEquals(PNG_SIGNATURE, photo.copyOfRange(0, PNG_SIGNATURE.size))
            assertNotNull(BitmapFactory.decodeByteArray(photo, 0, photo.size))
        } finally {
            // MQTT shutdown is intentionally omitted in this isolated process because a stalled
            // public broker can block engine.stop(); AndroidJUnitRunner tears the process down.
            if (!mqttEngineStarted) assertTrue(serverPort != null)
            key.fill(0)
            received.values.forEach { it.fill(0) }
            root.deleteRecursively()
            ready.delete()
            assertTrue(!root.exists())
            assertTrue(!ready.exists())
        }
    }

    private fun receiveDirectTcp(
        port: Int,
        ready: File,
        runId: String,
        expectedSender: String,
        receiver: String,
        key: ByteArray,
        store: FileTransferChunkStore,
        manifests: MutableMap<String, FileTransferManifestFfi>,
        received: MutableMap<String, ByteArray>,
    ) {
        ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 120_000
            ready.writeText("ready")
            while (received.size < 2) {
                server.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val input = DataInputStream(socket.getInputStream())
                    val length = input.readInt()
                    require(length in 1..MAX_TEST_FRAME_BYTES)
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    val text = bytes.toString(Charsets.UTF_8)
                    bytes.fill(0)
                    handlePacket(text, runId, expectedSender, receiver, key, store, manifests, received)
                    socket.getOutputStream().write(ACK)
                    socket.getOutputStream().flush()
                }
            }
        }
    }

    private fun receiveMqtt(
        runId: String,
        expectedSender: String,
        receiver: String,
        key: ByteArray,
        store: FileTransferChunkStore,
        manifests: MutableMap<String, FileTransferManifestFfi>,
        received: MutableMap<String, ByteArray>,
    ) {
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline && received.size < 2) {
            RustBridge.drainEvents().forEach { event ->
                if (event.eventType == "message_received" && event.senderId == expectedSender) {
                    event.text?.let {
                        handlePacket(it, runId, expectedSender, receiver, key, store, manifests, received)
                    }
                }
            }
            if (received.size < 2) Thread.sleep(250)
        }
    }

    private fun handlePacket(
        text: String,
        runId: String,
        expectedSender: String,
        receiver: String,
        key: ByteArray,
        store: FileTransferChunkStore,
        manifests: MutableMap<String, FileTransferManifestFfi>,
        received: MutableMap<String, ByteArray>,
    ) {
        val fields = text.split('|')
        if (fields.size < 5 || fields[0] != PREFIX || fields[2] != runId) return
        when (fields[1]) {
            "offer" -> {
                require(fields.size == 5)
                val kind = fields[3]
                val manifest = parseFileTransferManifest(decode(fields[4]))
                assertEquals(expectedSender, manifest.senderNodeId)
                assertEquals(receiver, manifest.recipientNodeId)
                assertEquals(1u, manifest.chunkCount)
                manifests[kind] = manifest
                store.storeManifest(manifest.transferIdHex, manifest.manifestBytes)
            }
            "chunk" -> {
                require(fields.size == 6)
                val kind = fields[3]
                val index = fields[4].toUInt()
                val manifest = manifests[kind] ?: return
                val ciphertext = decode(fields[5])
                store.storeEncryptedChunk(manifest.transferIdHex, index.toInt(), ciphertext)
                val plaintext = decryptFileTransferChunk(manifest.manifestBytes, key, index, ciphertext)
                assertEquals(manifest.fileSha256Hex, sha256(plaintext))
                received.putIfAbsent(kind, plaintext)
                ciphertext.fill(0)
            }
        }
    }

    private fun expectedFileBytes(): ByteArray = ByteArray(4_096) { index -> (index % 239).toByte() }

    private fun decode(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val PREFIX = "APUFILETEST1"
        private const val MAX_TEST_FRAME_BYTES = 256 * 1024
        private val ACK = byteArrayOf(0x41, 0x43, 0x4b)
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
