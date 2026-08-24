package com.vladimir.messenger.data.file

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LanDirectChannelTest {

    @Test
    fun directChannelDeliversFramesToIncomingRoute() = runBlocking {
        val server = LanDirectChannel()
        server.myNodeId = "pk_receiver_local_1"
        val received = CompletableDeferred<List<String>>()
        server.incomingRoute = { senderId, chatId, messageId, text ->
            received.complete(listOf(senderId, chatId, messageId, text))
            true
        }
        server.startServer()
        assertTrue(server.listenPort in 1024..65535)

        val client = LanDirectChannel()
        client.myNodeId = "pk_sender_local_1"
        assertTrue(client.openChannel("pk_receiver_local_1", "127.0.0.1", server.listenPort))
        assertTrue(client.hasChannel("pk_receiver_local_1"))
        assertTrue(client.sendPacket("pk_receiver_local_1", "chat-1", "msg-1", "APUFILETEST1|chunk|body"))

        val frame = withTimeout(5_000) { received.await() }
        assertEquals("pk_sender_local_1", frame[0])
        assertEquals("chat-1", frame[1])
        assertEquals("msg-1", frame[2])
        assertEquals("APUFILETEST1|chunk|body", frame[3])

        client.closeAll()
        server.closeAll()
        assertFalse(client.hasChannel("pk_receiver_local_1"))
    }

    @Test
    fun switchingTransportFallsBackToMeshWhenLanUnavailable() = runBlocking {
        val meshPayloads = mutableListOf<String>()
        val mesh = PacketTransport { _, _, _, text ->
            if (LanDirectChannel.isLanSignalText(text)) {
                // Mesh is "reachable" for data but the signal request fails:
                // no LAN offer will ever arrive, the channel must not block.
                false
            } else {
                meshPayloads.add(text)
                true
            }
        }
        val lan = LanDirectChannel()
        lan.myNodeId = "pk_sender"
        val switching = SwitchingPacketTransport(mesh, lan)

        assertTrue(switching.send("msg-1", "chat-1", "pk_receiver", "APUFILETEST1|offer|abc"))
        assertEquals(listOf("APUFILETEST1|offer|abc"), meshPayloads)
        // Establish failures are rate-limited: the second packet must not retry LAN.
        assertTrue(switching.send("msg-2", "chat-1", "pk_receiver", "APUFILETEST1|chunk|def"))
        assertEquals(2, meshPayloads.size)
        lan.closeAll()
    }

    @Test
    fun lanSignalTextsAreParsedStrictly() {
        assertTrue(LanDirectChannel.isLanSignalText("APULAN1|req"))
        assertTrue(LanDirectChannel.isLanSignalText("APULAN1|offer|192.168.1.5|41234"))
        assertFalse(LanDirectChannel.isLanSignalText("APUFILETEST1|chunk"))

        val lan = LanDirectChannel()
        assertEquals("APULAN1|req", lan.buildRequestText())

        val endpoint = lan.parseOfferText("APULAN1|offer|192.168.1.5|41234")
        assertEquals("192.168.1.5", endpoint?.hostAddress)
        assertEquals(41234, endpoint?.port)

        assertEquals(null, lan.parseOfferText("APULAN1|offer|not-an-ip|70000"))
        assertEquals(null, lan.parseOfferText("APULAN1|offer|192.168.1.5"))
        assertEquals(null, lan.parseOfferText("APUFILETEST1|chunk"))
    }

    @Test
    fun channelIsUsedWhenOpenAndSignalGoesToMesh() = runBlocking {
        val server = LanDirectChannel()
        server.myNodeId = "pk_receiver"
        val received = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        server.incomingRoute = { _, _, _, text ->
            received.add(text)
            gate.complete(Unit)
            true
        }
        server.startServer()

        val client = LanDirectChannel()
        client.myNodeId = "pk_sender"
        assertTrue(client.openChannel("pk_receiver", "127.0.0.1", server.listenPort))

        val meshPayloads = mutableListOf<String>()
        val mesh = PacketTransport { _, _, _, text ->
            meshPayloads.add(text)
            true
        }
        val switching = SwitchingPacketTransport(mesh, client)

        // Data packet: channel is open -> must ride the socket, not the mesh.
        assertTrue(switching.send("msg-1", "chat-1", "pk_receiver", "APUFILETEST1|chunk|via-lan"))
        withTimeout(5_000) { gate.await() }
        assertEquals(listOf("APUFILETEST1|chunk|via-lan"), received)
        assertTrue(meshPayloads.isEmpty())

        // LAN signal texts always ride the mesh, even with an open channel.
        assertTrue(switching.send("lan-1", "chat-1", "pk_receiver", "APULAN1|req"))
        assertEquals(listOf("APULAN1|req"), meshPayloads)

        client.closeAll()
        server.closeAll()
    }
}
