package com.vladimir.messenger.data.swarm

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.concurrent.thread

class Ipv6NetworkProxyTest {

    private class FakeEngineSink : Ipv6ProxyEngineSink {
        data class Applied(val host: String, val port: Int, val username: String, val password: String)

        val setCalls = mutableListOf<Applied>()
        var clearCount = 0
        var wakeCount = 0

        override fun setSocks5Proxy(host: String, port: Int, username: String, password: String) {
            setCalls += Applied(host, port, username, password)
        }

        override fun clearSocks5Proxy() {
            clearCount += 1
        }

        override fun wakeNetwork() {
            wakeCount += 1
        }
    }

    @After
    fun tearDown() {
        Ipv6ProxyRuntime.apply(Ipv6ProxyConfig(enabled = false), FakeEngineSink())
    }

    @Test
    fun recommendedShopUrlMatchesSpecification() {
        assertEquals("https://ru.shopproxy.net/buy-proxy/ipv6/", IPV6_RECOMMENDED_SHOP_URL)
    }

    @Test
    fun parsesAllCommonShopFormatsIncludingIpv6AndComplexPasswords() {
        // 1. shopproxy / proxy6: ip:port:login:password (пароль со знаками ':' и '@')
        val colon4 = Ipv6ProxyParser.parseQuickInput("185.44.12.90:10800:buyer42:p@ss:word!")
        assertNotNull(colon4)
        assertEquals(Ipv6ProxyProtocol.SOCKS5, colon4!!.protocol)
        assertEquals("185.44.12.90", colon4.host)
        assertEquals(10800, colon4.port)
        assertEquals("buyer42", colon4.username)
        assertEquals("p@ss:word!", colon4.password)
        assertFalse(colon4.isIpv6Host)

        // 2. Прямой IPv6 в скобках: [2a00:1fa0:4412::1]:1080:login:pass
        val v6Colon = Ipv6ProxyParser.parseQuickInput("[2A00:1FA0:4412::1]:1080:v6user:sec:ret")
        assertNotNull(v6Colon)
        assertEquals("2a00:1fa0:4412::1", v6Colon!!.host)
        assertEquals(1080, v6Colon.port)
        assertEquals("v6user", v6Colon.username)
        assertEquals("sec:ret", v6Colon.password)
        assertTrue(v6Colon.isIpv6Host)
        assertEquals("[2a00:1fa0:4412::1]:1080", v6Colon.formattedEndpoint())
        assertFalse(v6Colon.safeSummary().contains("sec:ret"))
        assertFalse(v6Colon.safeSummary().contains("v6user"))

        // 3. URI socks5:// и http://
        val socksUri = Ipv6ProxyParser.parseQuickInput("socks5://alice:s3cr3t@[2a00:1fa0::9]:1085")
        assertNotNull(socksUri)
        assertEquals(Ipv6ProxyProtocol.SOCKS5, socksUri!!.protocol)
        assertEquals("2a00:1fa0::9", socksUri.host)
        assertEquals(1085, socksUri.port)
        assertEquals("alice", socksUri.username)
        assertEquals("s3cr3t", socksUri.password)

        val httpUri = Ipv6ProxyParser.parseQuickInput("http://bob:pw@gate.shopproxy.net:8080")
        assertNotNull(httpUri)
        assertEquals(Ipv6ProxyProtocol.HTTP, httpUri!!.protocol)
        assertEquals("gate.shopproxy.net", httpUri.host)
        assertEquals(8080, httpUri.port)
        assertEquals("bob", httpUri.username)
        assertEquals("pw", httpUri.password)

        // 4. login:password@host:port без схемы (со знаком '@' внутри пароля)
        val atFormat = Ipv6ProxyParser.parseQuickInput("login1:pa@ss@185.10.20.30:9050", Ipv6ProxyProtocol.HTTP)
        assertNotNull(atFormat)
        assertEquals(Ipv6ProxyProtocol.HTTP, atFormat!!.protocol)
        assertEquals("185.10.20.30", atFormat.host)
        assertEquals(9050, atFormat.port)
        assertEquals("login1", atFormat.username)
        assertEquals("pa@ss", atFormat.password)

        // 5. Многострочная карточка из личного кабинета (с пробелами и без)
        val kv = Ipv6ProxyParser.parseQuickInput(
            """
            IP:185.10.20.30
            Port:1080
            Login:user_kv
            Password:pass_kv
            Type:SOCKS5
            """.trimIndent()
        )
        assertNotNull(kv)
        assertEquals("185.10.20.30", kv!!.host)
        assertEquals(1080, kv.port)
        assertEquals("user_kv", kv.username)
        assertEquals("pass_kv", kv.password)
    }

    @Test
    fun fromFieldsSupportsSeparateInputsSmartPasteAndValidation() {
        val separate = Ipv6ProxyParser.fromFields(
            enabled = true,
            protocol = Ipv6ProxyProtocol.SOCKS5,
            hostInput = " [2a00:1fa0::5] ",
            portInput = "1080",
            usernameInput = " u1 ",
            passwordInput = "p1",
        )
        assertNotNull(separate)
        assertEquals("2a00:1fa0::5", separate!!.host)
        assertEquals(1080, separate.port)

        // Вставка одной строкой прямо в поле хоста при пустом порте
        val smart = Ipv6ProxyParser.fromFields(
            enabled = true,
            protocol = Ipv6ProxyProtocol.SOCKS5,
            hostInput = "http://u2:p2@185.1.2.3:3128",
            portInput = "",
            usernameInput = "",
            passwordInput = "",
        )
        assertNotNull(smart)
        assertEquals(Ipv6ProxyProtocol.HTTP, smart!!.protocol)
        assertEquals("185.1.2.3", smart.host)
        assertEquals(3128, smart.port)
        assertEquals("u2", smart.username)
        assertEquals("p2", smart.password)

        // Отклонение некорректных входных данных
        assertNull(Ipv6ProxyParser.fromFields(true, Ipv6ProxyProtocol.SOCKS5, "", "1080", "", ""))
        assertNull(Ipv6ProxyParser.fromFields(true, Ipv6ProxyProtocol.SOCKS5, "1.2.3.4", "70000", "", ""))
        assertNull(Ipv6ProxyParser.fromFields(true, Ipv6ProxyProtocol.SOCKS5, "1.2.3.4", "1080", "", "orphanPass"))
        assertNull(Ipv6ProxyParser.fromFields(true, Ipv6ProxyProtocol.SOCKS5, "1.2.3.4", "1080", "bad\r\nuser", "p"))
        assertNull(Ipv6ProxyParser.parseQuickInput("2a00:1fa0:4000:1"))
    }

    @Test
    fun bypassesLoopbackAndLanWhileProxyingExternalTraffic() {
        for (local in listOf("127.0.0.1", "localhost", "::1", "192.168.0.10", "10.1.2.3", "172.16.5.4", "172.31.9.9", "fe80::1", "fd12::1", "node.local")) {
            assertTrue("Expected bypass for $local", Ipv6ProxyParser.isBypassHost(local))
        }
        for (external in listOf("185.44.12.90", "172.32.0.1", "2a00:1fa0::1", "p2p-relay.apuchat.workers.dev", "broker.hivemq.com")) {
            assertFalse("Expected external route for $external", Ipv6ProxyParser.isBypassHost(external))
        }

        val sink = FakeEngineSink()
        val cfg = Ipv6ProxyConfig(
            enabled = true,
            protocol = Ipv6ProxyProtocol.SOCKS5,
            host = "185.44.12.90",
            port = 1080,
            username = "u",
            password = "p",
        )
        Ipv6ProxyRuntime.apply(cfg, sink)
        assertEquals(1, sink.setCalls.size)
        assertEquals(FakeEngineSink.Applied("185.44.12.90", 1080, "u", "p"), sink.setCalls.single())
        assertEquals(1, sink.wakeCount)

        val relayProxies = Ipv6ProxyRuntime.selectProxiesForUri(URI("https://p2p-relay.apuchat.workers.dev/send"))
        assertEquals(Proxy.Type.SOCKS, relayProxies.first().type())
        val lanProxies = Ipv6ProxyRuntime.selectProxiesForUri(URI("http://192.168.1.50:9000/sync"))
        assertEquals(listOf(Proxy.NO_PROXY), lanProxies)

        // Отключение прокси очищает ядро ровно один раз; повторный неактивный вызов не трогает ядро.
        Ipv6ProxyRuntime.apply(cfg.copy(enabled = false), sink)
        assertEquals(1, sink.clearCount)
        Ipv6ProxyRuntime.apply(cfg.copy(enabled = false), sink)
        assertEquals(1, sink.clearCount)
    }

    @Test
    fun reapplyingSocksProxyKeepsConnectionsButStillSetsTheNativeProxy() {
        val sink = FakeEngineSink()
        val config = Ipv6ProxyConfig(true, Ipv6ProxyProtocol.SOCKS5, "127.0.0.1", 1080, "user", "old")

        Ipv6ProxyRuntime.apply(config, sink)
        Ipv6ProxyRuntime.apply(config, sink)
        assertEquals(2, sink.setCalls.size) // also restores a fresh or overwritten native setting
        assertEquals(1, sink.wakeCount)

        val changed = config.copy(password = "new")
        Ipv6ProxyRuntime.apply(changed, sink)
        assertEquals(3, sink.setCalls.size)
        assertEquals("new", sink.setCalls.last().password)
        assertEquals(2, sink.wakeCount)

        Ipv6ProxyRuntime.apply(changed.copy(enabled = false), sink)
        Ipv6ProxyRuntime.apply(changed.copy(enabled = false), sink)
        assertEquals(1, sink.clearCount)
        assertEquals(3, sink.wakeCount)
    }

    @Test
    fun reapplyingHttpProxyReusesBridgeWithoutWakingConnections() {
        val sink = FakeEngineSink()
        val config = Ipv6ProxyConfig(true, Ipv6ProxyProtocol.HTTP, "127.0.0.1", 8080, "user", "old")

        Ipv6ProxyRuntime.apply(config, sink)
        assertEquals(1, sink.setCalls.size)
        val firstBridge = sink.setCalls.single()
        assertEquals("127.0.0.1", firstBridge.host)
        assertTrue(firstBridge.port > 0)
        Ipv6ProxyRuntime.apply(config, sink)
        assertEquals(listOf(firstBridge, firstBridge), sink.setCalls)
        assertEquals(1, sink.wakeCount)

        val changed = config.copy(password = "new")
        Ipv6ProxyRuntime.apply(changed, sink)
        assertEquals(3, sink.setCalls.size)
        assertEquals(2, sink.wakeCount)
        Ipv6ProxyRuntime.apply(changed.copy(enabled = false), sink)
        Ipv6ProxyRuntime.apply(changed.copy(enabled = false), sink)
        assertEquals(1, sink.clearCount)
        assertEquals(3, sink.wakeCount)
    }

    @Test
    fun socks5ProbeVerifiesGreetingAndRfc1929Authentication() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val acceptThread = thread(isDaemon = true) {
                repeat(2) {
                    server.accept().use { client ->
                        val input = client.getInputStream()
                        val output = client.getOutputStream()
                        val head = readExactBytes(input, 2)
                        readExactBytes(input, head[1].toInt() and 0xFF)
                        output.write(byteArrayOf(0x05, 0x02))
                        output.flush()
                        val verUlen = readExactBytes(input, 2)
                        val user = String(readExactBytes(input, verUlen[1].toInt() and 0xFF), StandardCharsets.UTF_8)
                        val plen = readExactBytes(input, 1)[0].toInt() and 0xFF
                        val pass = String(readExactBytes(input, plen), StandardCharsets.UTF_8)
                        val ok = user == "good" && pass == "secret"
                        output.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
                        output.flush()
                    }
                }
            }

            val goodCfg = Ipv6ProxyConfig(true, Ipv6ProxyProtocol.SOCKS5, "127.0.0.1", server.localPort, "good", "secret")
            val okResult = Ipv6ProxyRuntime.probe(goodCfg, timeoutMs = 3_000)
            assertTrue("Expected Ok, got $okResult", okResult is Ipv6ProxyProbeResult.Ok)

            val badCfg = goodCfg.copy(password = "wrong")
            val failResult = Ipv6ProxyRuntime.probe(badCfg, timeoutMs = 3_000)
            assertEquals(Ipv6ProxyProbeResult.AuthFailed, failResult)

            acceptThread.join(3_000)
        }
    }

    @Test
    fun httpConnectBridgeTunnelsSocks5TrafficWithProxyAuthorization() {
        val expectedToken = Base64.getEncoder().encodeToString("shopuser:shoppass".toByteArray(StandardCharsets.UTF_8))

        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { echoTarget ->
            val echoThread = thread(isDaemon = true) {
                echoTarget.accept().use { s ->
                    val msg = readExactBytes(s.getInputStream(), 9)
                    s.getOutputStream().write("OK:".toByteArray() + msg)
                    s.getOutputStream().flush()
                }
            }

            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { fakeHttpProxy ->
                val proxyThread = thread(isDaemon = true) {
                    repeat(2) {
                        fakeHttpProxy.accept().use { client ->
                            val reqBuf = ByteArrayOutputStream()
                            val inp = client.getInputStream()
                            val out = client.getOutputStream()
                            while (!reqBuf.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
                                val b = inp.read()
                                if (b < 0) return@use
                                reqBuf.write(b)
                            }
                            val headers = reqBuf.toString("UTF-8")
                            if (!headers.contains("Proxy-Authorization: Basic $expectedToken")) {
                                out.write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray())
                                out.flush()
                                return@use
                            }
                            val authority = headers.lineSequence().first().split(' ')[1]
                            val host = authority.substringBefore(':')
                            val port = authority.substringAfter(':').toInt()
                            Socket(host, port).use { target ->
                                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                                out.flush()
                                val reqPayload = readExactBytes(inp, 9)
                                target.getOutputStream().write(reqPayload)
                                target.getOutputStream().flush()
                                val respPayload = readExactBytes(target.getInputStream(), 12)
                                out.write(respPayload)
                                out.flush()
                            }
                        }
                    }
                }

                // 1) Неверный пароль -> AuthFailed
                val wrongHttp = Ipv6ProxyConfig(true, Ipv6ProxyProtocol.HTTP, "127.0.0.1", fakeHttpProxy.localPort, "shopuser", "bad")
                assertEquals(
                    Ipv6ProxyProbeResult.AuthFailed,
                    Ipv6ProxyRuntime.probe(wrongHttp, timeoutMs = 3_000, httpProbeHost = "127.0.0.1", httpProbePort = echoTarget.localPort),
                )

                // 2) Верный пароль -> локальный SOCKS5-мост прокачивает трафик движка через HTTP CONNECT
                val sink = FakeEngineSink()
                val validHttp = wrongHttp.copy(password = "shoppass")
                Ipv6ProxyRuntime.apply(validHttp, sink)
                val applied = sink.setCalls.single()
                assertEquals("127.0.0.1", applied.host)
                assertTrue(applied.port > 0)

                Socket().use { engineClient ->
                    engineClient.soTimeout = 3_000
                    engineClient.connect(InetSocketAddress("127.0.0.1", applied.port), 3_000)
                    val cOut = engineClient.getOutputStream()
                    val cIn = engineClient.getInputStream()

                    cOut.write(byteArrayOf(0x05, 0x01, 0x00))
                    cOut.flush()
                    assertArrayEquals(byteArrayOf(0x05, 0x00), readExactBytes(cIn, 2))

                    val hostBytes = "127.0.0.1".toByteArray(StandardCharsets.UTF_8)
                    val targetPort = echoTarget.localPort
                    val connectReq = ByteArrayOutputStream().apply {
                        write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
                        write(hostBytes)
                        write((targetPort shr 8) and 0xFF)
                        write(targetPort and 0xFF)
                    }.toByteArray()
                    cOut.write(connectReq)
                    cOut.flush()

                    val socksReply = readExactBytes(cIn, 10)
                    assertEquals(0x05.toByte(), socksReply[0])
                    assertEquals(0x00.toByte(), socksReply[1])

                    cOut.write("APU-IPV6!".toByteArray(StandardCharsets.UTF_8))
                    cOut.flush()
                    val echoed = String(readExactBytes(cIn, 12), StandardCharsets.UTF_8)
                    assertEquals("OK:APU-IPV6!", echoed)
                }

                echoThread.join(3_000)
                proxyThread.join(3_000)
            }
        }
    }
}
