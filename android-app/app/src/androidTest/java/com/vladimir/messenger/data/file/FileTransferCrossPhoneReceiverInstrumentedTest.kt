package com.vladimir.messenger.data.file

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.data.RustBridge
import java.io.File
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
        require(runId.matches(Regex("^[0-9a-f]{16}$")) && key.size == 32)
        val root = File(context.noBackupFilesDir, "file-cross-phone-receiver-test-v1")
        val ready = File(context.noBackupFilesDir, "file-cross-phone-receiver-ready-v1")
        root.deleteRecursively()
        ready.delete()
        val store = FileTransferChunkStore(root, maxStoreBytes = 2L * 1024 * 1024)
        val manifests = mutableMapOf<String, FileTransferManifestFfi>()
        val received = mutableMapOf<String, ByteArray>()
        try {
            val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            val publicKey = prefs.getString("existing_public_key", null)
                ?: prefs.getString("node_id", null)
                ?: error("Missing receiver identity")
            val privateKey = prefs.getString("existing_private_key", null) ?: publicKey
            val displayName = prefs.getString("display_name", "Receiver") ?: "Receiver"
            assertTrue(RustBridge.initialize(displayName, publicKey, privateKey, null))
            val receiver = RustBridge.nodeId() ?: error("Receiver engine has no node ID")
            ready.writeText("ready")
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline && received.size < 2) {
                RustBridge.drainEvents().forEach { event ->
                    if (event.eventType != "message_received" || event.senderId != expectedSender) {
                        return@forEach
                    }
                    val text = event.text ?: return@forEach
                    val fields = text.split('|')
                    if (fields.size < 5 || fields[0] != PREFIX || fields[2] != runId) return@forEach
                    when (fields[1]) {
                        "offer" -> {
                            if (fields.size != 5) return@forEach
                            val kind = fields[3]
                            val manifest = parseFileTransferManifest(decode(fields[4]))
                            assertEquals(expectedSender, manifest.senderNodeId)
                            assertEquals(receiver, manifest.recipientNodeId)
                            assertEquals(1u, manifest.chunkCount)
                            manifests[kind] = manifest
                            store.storeManifest(manifest.transferIdHex, manifest.manifestBytes)
                        }
                        "chunk" -> {
                            if (fields.size != 6) return@forEach
                            val kind = fields[3]
                            val index = fields[4].toUInt()
                            val manifest = manifests[kind] ?: return@forEach
                            val ciphertext = decode(fields[5])
                            store.storeEncryptedChunk(manifest.transferIdHex, index.toInt(), ciphertext)
                            val plaintext = decryptFileTransferChunk(
                                manifest.manifestBytes,
                                key,
                                index,
                                ciphertext,
                            )
                            assertEquals(manifest.fileSha256Hex, sha256(plaintext))
                            received.putIfAbsent(kind, plaintext)
                            ciphertext.fill(0)
                        }
                    }
                }
                if (received.size < 2) Thread.sleep(250)
            }
            assertEquals(setOf("file", "photo"), received.keys)
            assertArrayEquals(expectedFileBytes(), received["file"])
            val photo = received["photo"]!!
            assertTrue(photo.size > 100)
            assertArrayEquals(PNG_SIGNATURE, photo.copyOfRange(0, PNG_SIGNATURE.size))
            assertNotNull(BitmapFactory.decodeByteArray(photo, 0, photo.size))
        } finally {
            RustBridge.shutdown()
            key.fill(0)
            received.values.forEach { it.fill(0) }
            root.deleteRecursively()
            ready.delete()
            assertTrue(!root.exists())
            assertTrue(!ready.exists())
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
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
