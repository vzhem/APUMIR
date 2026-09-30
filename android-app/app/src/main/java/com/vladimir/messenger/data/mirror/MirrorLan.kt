package com.vladimir.messenger.data.mirror

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.concurrent.thread

/**
 * р232: прямой канал устройство-устройство по локальной сети.
 *
 * Зеркальный канал идёт через воркер (общий, кадр 512 КиБ, потолок 24 МБ на
 * файл). Если оба устройства одной личности в одной сети (домашний Wi-Fi),
 * большой файл можно отдать напрямую: тот, у кого байты, поднимает на
 * свободном порту крошечный HTTP-сервер под одноразовым кодом и говорит
 * партнёру адрес; партнёр забирает файл сам. Образец - `ProfileSyncDirect`
 * (перенос копии профиля), здесь то же самое, но кусками и без чтения файла
 * в память целиком.
 *
 * Если устройства не видят друг друга (разные сети), подключение просто не
 * случится: карточка файла останется, байты не поедут - как и раньше.
 */
object MirrorLan {

    private const val TAG = "MirrorLan"

    /** Потолок прямого канала; выше - всё равно копия аккаунта. */
    const val MAX_BYTES = 1024L * 1024 * 1024

    /** Размер блока при отдаче и приёме. */
    private const val BLOCK_BYTES = 64 * 1024

    /** Сколько ждать того, кто придёт за файлом (открытие/докачка вручную). */
    private const val IDLE_TIMEOUT_MS = 10 * 60_000L

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .firstOrNull { !it.contains(':') }
    }.getOrNull()

    fun newToken(): String =
        String.format("%08x", java.security.SecureRandom().nextInt())

    /**
     * Отдающая сторона: поднимает сервер, отдаёт байты файла [totalBytes]
     * блоками через [read] (обычно - `FileTransferRouter.readMirrorFileChunk`,
     * он умеет и принятый плейнтекст, и расшифровку своих кусков).
     */
    class Sender(
        private val transferId: String,
        private val totalBytes: Long,
        private val read: suspend (offset: Long, size: Int) -> ByteArray?,
    ) {
        val token: String = newToken()

        @Volatile private var server: ServerSocket? = null
        @Volatile private var stopped = false

        /** Поднять сервер на свободном порту; null - не получилось. */
        fun start(): Int? = runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 1000
            server = socket
            thread(start = true, isDaemon = true, name = "apu-mirror-lan") {
                try {
                    val deadline = System.currentTimeMillis() + IDLE_TIMEOUT_MS
                    while (!stopped && System.currentTimeMillis() < deadline) {
                        val client = try {
                            socket.accept()
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        if (serve(client)) return@thread
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "lan serve failed: ${e.message}")
                } finally {
                    runCatching { socket.close() }
                    server = null
                }
            }
            socket.localPort
        }.getOrNull()

        private fun serve(socket: java.net.Socket): Boolean = socket.use { client ->
            client.soTimeout = 30_000
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return false
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
            }
            val path = requestLine.split(" ").getOrNull(1)?.trim('/') ?: ""
            if (path != token) {
                client.getOutputStream().write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                )
                return false
            }
            val out = client.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: $totalBytes\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            var offset = 0L
            while (offset < totalBytes) {
                val want = minOf(BLOCK_BYTES.toLong(), totalBytes - offset).toInt()
                val block = kotlinx.coroutines.runBlocking { read(offset, want) } ?: return false
                if (block.isEmpty()) return false
                out.write(block)
                offset += block.size
            }
            out.flush()
            Log.i(TAG, "lan serve done: $offset Б (${transferId.take(8)})")
            true
        }

        fun stop() {
            stopped = true
            runCatching { server?.close() }
        }
    }

    /**
     * Принимающая сторона: забрать файл блоками, каждый отдать в [sink]
     * (порядковый номер, последний ли, байты). true - скачался целиком.
     */
    suspend fun pull(
        host: String,
        port: Int,
        token: String,
        expectBytes: Long,
        sink: suspend (seq: Int, last: Boolean, bytes: ByteArray) -> Unit,
    ): Boolean = runCatching {
        val conn = (URL("http://$host:$port/$token").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 60_000
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "lan pull got $code")
                return@runCatching false
            }
            val input = conn.inputStream
            val buffer = ByteArray(BLOCK_BYTES)
            var seq = 0
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                val last = expectBytes > 0 && total >= expectBytes
                sink(seq, last, if (n == buffer.size) buffer.copyOf() else buffer.copyOf(n))
                seq += 1
                if (last) break
            }
            val done = expectBytes <= 0 || total >= expectBytes
            if (!done) Log.w(TAG, "lan pull short: $total из $expectBytes")
            done
        } finally {
            conn.disconnect()
        }
    }.getOrElse { error ->
        if (error is IOException) Log.w(TAG, "lan pull failed: ${error.message}") else Log.w(TAG, "lan pull error: ${error.message}")
        false
    }
}
