package com.vladimir.messenger.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Клиент домашней сети: разбор ответа и запрет на нелокальные адреса.
 *
 * Тест гоняет настоящий сокет на loopback — ровно тот путь, которым приёмник
 * забирает копию профиля и блоки зеркала (docs/AUDIT_2026-10-26.md, P1-1).
 */
class LanHttpGetTest {

    @Test
    fun readsStatusLengthAndBodyFromLoopbackServer() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val served = CountDownLatch(1)
        val worker = thread(isDaemon = true, name = "lan-http-test-server") {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    // Строка запроса и заголовки — до пустой строки.
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = "hello".toByteArray()
                    socket.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray()
                        )
                        write(body)
                        flush()
                    }
                }
            } catch (_: IOException) {
                // Сокет закрыли из теста — это ожидаемый путь выхода.
            } finally {
                served.countDown()
            }
        }
        try {
            LanHttpGet.open("127.0.0.1", server.localPort, "token", 2_000, 5_000).use { response ->
                assertEquals(5L, response.contentLength)
                assertEquals("hello", String(response.stream.readBytes()))
            }
            assertTrue("сервер не успел ответить", served.await(5, TimeUnit.SECONDS))
        } finally {
            runCatching { server.close() }
            worker.join(5_000)
        }
    }

    @Test
    fun refusesNonLocalAddressBeforeConnecting() {
        try {
            // Литеральный адрес: DNS для проверки не нужен, соединение не
            // открывается — клиент обязан отказаться заранее.
            LanHttpGet.open("8.8.8.8", 80, "token", 1_000, 1_000).close()
            fail("публичный адрес не должен открываться")
        } catch (_: IOException) {
            // Ожидаемо: адрес не локальный.
        }
    }
}
