package com.vladimir.messenger.data.file

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.data.RustBridge
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.createFileTransferManifest
import uniffi.p2p_core.encryptFileTransferChunk

@RunWith(AndroidJUnit4::class)
class FileTransferCrossPhoneSenderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sendsEncryptedFileAndPhotoToOtherPhone() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("file_run_id") ?: error("Missing run ID")
        val recipient = arguments.getString("recipient_node") ?: error("Missing recipient")
        val key = decode(arguments.getString("file_test_key") ?: error("Missing test key"))
        val tcpPort = arguments.getString("tcp_port")?.toIntOrNull()
        require(runId.matches(Regex("^[0-9a-f]{16}$")) && key.size == 32)
        require(tcpPort == null || tcpPort in 1024..65535)
        val fileBytes = ByteArray(4_096) { index -> (index % 239).toByte() }
        val photoBytes = createPhotoPng()
        try {
            val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            val publicKey = prefs.getString("existing_public_key", null)
                ?: prefs.getString("node_id", null)
                ?: error("Missing sender identity")
            val sender = if (tcpPort != null) {
                publicKey
            } else {
                val privateKey = prefs.getString("existing_private_key", null) ?: publicKey
                val displayName = prefs.getString("display_name", "Sender") ?: "Sender"
                assertTrue(RustBridge.initialize(displayName, publicKey, privateKey, null))
                Thread.sleep(12_000)
                RustBridge.nodeId() ?: error("Sender engine has no node ID")
            }
            sendOne(runId, "file", "cross-phone-test.bin", "application/octet-stream", fileBytes, sender, recipient, key, tcpPort)
            sendOne(runId, "photo", "cross-phone-photo.png", "image/png", photoBytes, sender, recipient, key, tcpPort)
        } finally {
            if (tcpPort == null) RustBridge.shutdown()
            key.fill(0)
            fileBytes.fill(0)
            photoBytes.fill(0)
        }
    }

    private fun sendOne(
        runId: String,
        kind: String,
        name: String,
        mediaType: String,
        plaintext: ByteArray,
        sender: String,
        recipient: String,
        key: ByteArray,
        tcpPort: Int?,
    ) {
        val now = System.currentTimeMillis()
        val manifest = createFileTransferManifest(
            sender,
            recipient,
            name,
            mediaType,
            plaintext.size.toULong(),
            MessageDigest.getInstance("SHA-256").digest(plaintext),
            now,
            now + 24 * 60 * 60 * 1_000L,
        )
        assertEquals(1u, manifest.chunkCount)
        val ciphertext = encryptFileTransferChunk(manifest.manifestBytes, key, 0u, plaintext)
        try {
            repeat(if (tcpPort == null) 2 else 1) { attempt ->
                publish(
                    recipient,
                    sender,
                    "offer-$kind-$attempt-$runId",
                    runId,
                    "$PREFIX|offer|$runId|$kind|${encode(manifest.manifestBytes)}",
                    tcpPort,
                )
                Thread.sleep(1_500)
                publish(
                    recipient,
                    sender,
                    "chunk-$kind-$attempt-$runId",
                    runId,
                    "$PREFIX|chunk|$runId|$kind|0|${encode(ciphertext)}",
                    tcpPort,
                )
                Thread.sleep(1_500)
            }
        } finally {
            ciphertext.fill(0)
        }
    }

    private fun publish(
        recipient: String,
        sender: String,
        messageId: String,
        runId: String,
        text: String,
        tcpPort: Int?,
    ) {
        if (tcpPort != null) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..256 * 1024)
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", tcpPort), 15_000)
                socket.soTimeout = 15_000
                val output = DataOutputStream(socket.getOutputStream())
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
                val ack = ByteArray(3)
                java.io.DataInputStream(socket.getInputStream()).readFully(ack)
                assertArrayEquals(byteArrayOf(0x41, 0x43, 0x4b), ack)
            }
            bytes.fill(0)
        } else {
            val payload = "$sender|$messageId|file-test-$runId|$recipient|$text"
            assertTrue(RustBridge.sendMessageMqtt(recipient, payload))
        }
    }

    private fun createPhotoPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, Color.rgb((x * 2) % 256, (y * 2) % 256, (x + y) % 256))
            }
        }
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun decode(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    companion object {
        private const val PREFIX = "APUFILETEST1"
    }
}
