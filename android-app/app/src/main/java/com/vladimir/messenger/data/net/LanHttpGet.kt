package com.vladimir.messenger.data.net

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Один HTTP/1.1 GET по домашней сети — без `HttpURLConnection`.
 *
 * Зачем свой клиент. С Android 9 политика cleartext (`android:usesCleartextTraffic`
 * по умолчанию выключена) не даёт `HttpURLConnection` открыть `http://`-адрес:
 * бросается `UnknownServiceException`, и обе LAN-функции — «Синхронизировать
 * аккаунт» напрямую ([com.vladimir.messenger.data.backup.ProfileSyncDirect]) и
 * зеркало по Wi-Fi ([com.vladimir.messenger.data.mirror.MirrorLan]) — молча не
 * работали на всех современных телефонах (docs/AUDIT_2026-10-26.md, P1-1).
 *
 * Разрешить cleartext всему приложению (`usesCleartextTraffic="true"`) ради двух
 * функций нельзя: тогда молча заработает ЛЮБОЙ http-запрос, включая будущие.
 * Поэтому здесь узкий и явный путь: соединение открывается только к приватному
 * адресу (10/8, 172.16/12, 192.168/16, link-local, loopback), а платформенная
 * политика для остальных сетевых вызовов остаётся строгой. Содержимое обеих
 * передач и так зашифровано на уровне приложения (архив профиля с паролем,
 * блоки зеркала).
 *
 * Протокол нарочно оставлен HTTP/1.1: отдающая сторона — наш же сервер на
 * `ServerSocket` ([com.vladimir.messenger.data.backup.ProfileSyncDirect.ShareServer] и
 * [com.vladimir.messenger.data.mirror.MirrorLan.Sender]), и старые сборки на другом телефоне должны остаться
 * совместимыми с новым приёмником.
 */
object LanHttpGet {

    /** Ответ сервера: поток тела и известная длина (или -1, если её не было). */
    class Response internal constructor(
        val contentLength: Long,
        val stream: InputStream,
        private val socket: Socket,
    ) : Closeable {
        override fun close() {
            runCatching { stream.close() }
            runCatching { socket.close() }
        }
    }

    /**
     * Открыть `GET /path` к [host]:[port].
     *
     * @throws IOException если адрес не локальный, соединение не открылось,
     *         сервер ответил не 200 или заголовки не разобрались.
     */
    fun open(
        host: String,
        port: Int,
        path: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Response {
        val target = resolveLocal(host)
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(target, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            val out = socket.getOutputStream()
            out.write(
                (
                    "GET /$path HTTP/1.1\r\n" +
                        "Host: $host:$port\r\n" +
                        "Accept: application/octet-stream\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            out.flush()

            val input = BufferedInputStream(socket.getInputStream())
            val status = readLine(input)
                ?: throw IOException("пустой ответ от $host:$port")
            // "HTTP/1.1 200 OK": код — второе слово статусной строки.
            val code = status.split(' ').getOrNull(1)?.toIntOrNull()
            if (code != 200) throw IOException("ответ $code от $host:$port")

            var length = -1L
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    length = line.substringAfter(':').trim().toLongOrNull() ?: -1L
                }
            }
            return Response(length, input, socket)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    /**
     * Адрес, к которому разрешено подключаться: только приватный.
     *
     * Проверяется РАЗРЕШЁННЫЙ адрес, а не строка из QR: так имя, указывающее на
     * публичный IP, не проскочит проверку. Если у имени несколько адресов,
     * берётся первый локальный.
     */
    private fun resolveLocal(host: String): InetAddress {
        val addresses = InetAddress.getAllByName(host)
        return addresses.firstOrNull { it.isLocalOnly() }
            ?: throw IOException("адрес $host не является локальным")
    }

    private fun InetAddress.isLocalOnly(): Boolean =
        isSiteLocalAddress || isLoopbackAddress || isLinkLocalAddress || isAnyLocalAddress

    /** Строка заголовка; null — поток кончился раньше, чем началась строка. */
    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.toString().removeSuffix("\r")
            if (builder.length > MAX_HEADER_LINE) return null
            builder.append(byte.toChar())
        }
    }

    private const val MAX_HEADER_LINE = 8 * 1024
}
