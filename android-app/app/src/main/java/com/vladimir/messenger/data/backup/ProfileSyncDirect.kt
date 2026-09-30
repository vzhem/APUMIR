package com.vladimir.messenger.data.backup

// =============================================================================
// PROFILESYNCDIRECT.KT — «Синхронизировать аккаунт»: ПРЯМОЙ перенос копии
// =============================================================================
// Раунд 224 (владелец: «не в облаке хранилось — телефон напрямую передал все
// свои данные»); облачная версия р223 убрана целиком.
//
// Как работает (ничего нигде не хранится):
//  1. Устройство-источник собирает зашифрованную копию профиля (тот же архив
//     .apubak из [ProfileBackup], паролем человека) во временный файл в
//     закрытой папке приложения и поднимает ОДНОРАЗОВЫЙ сервер на своём
//     Wi-Fi-адресе. На экране - QR и код вида «192.168.1.5:48126 a1b2c3d4».
//  2. Устройство-приёмник в том же окне вводит адрес и код (или сканирует QR
//     любым сканером) и САМО забирает копию напрямую: GET http://<адрес>/<код>.
//  3. Дальше - штатный путь файла-копии: открыть паролем, показать, чья
//     копия, подтвердить, перезапуститься ([ProfileBackup.stage/confirm]).
//  На пути между устройствами - зашифрованные байты: даже в открытом HTTP
//  идёт только шифрованный архив, пароль по сети не ходит.
//  ВАЖНО для эмулятора/ПК: приёмник подключается К источнику (исходящие
//  соединения открыты везде). Передать «на эмулятор» можно, только если
//  эмулятор сам забирает; обратное направление требует adb reverse.
// =============================================================================

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import kotlin.concurrent.thread

object ProfileSyncDirect {

    private const val TAG = "ProfileSyncDirect"
    const val DEFAULT_PORT = 48126

    /** Верхняя граница скачиваемой копии (копия без медиа обычно 1-5 МБ). */
    private const val MAX_COPY_BYTES = 32L * 1024 * 1024

    private val RANDOM = SecureRandom()

    /** Адрес раздачи: где и под каким одноразовым кодом лежит копия. */
    data class ShareAddress(
        val host: String,
        val port: Int,
        val token: String,
    ) {
        /** Для QR (любой сканер покажет текст, который человек перенесёт руками). */
        val qrPayload: String get() = "apu://sync|$host|$port|$token"

        /** Для ручного ввода на приёмнике: «192.168.1.5:48126» + код. */
        val humanAddress: String get() = "$host:$port"
    }

    fun newToken(): String = String.format("%08x", RANDOM.nextInt())

    /** Первый Wi-Fi/LAN-адрес этого устройства (для показа на экране). */
    fun lanIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .firstOrNull { !it.contains(':') }
    }.getOrNull()

    // ── Источник: одноразовый сервер раздачи ────────────────────────────────

    /**
     * Поднимает сервер на [port]; первому, кто правильно назовёт [token],
     * отдаёт файл [provideFile] и закрывается. [onDone] - true: копия забрана,
     * false: ошибка сервера. [onDone] зовётся на главном потоке.
     */
    class ShareServer(
        private val port: Int,
        private val token: String,
        private val provideFile: () -> File?,
        private val onDone: (Boolean, String?) -> Unit,
    ) {
        private var server: ServerSocket? = null
        @Volatile private var cancelled = false
        @Volatile private var served = false
        private val main = Handler(Looper.getMainLooper())

        fun start() {
            thread(start = true, isDaemon = true, name = "apu-profile-share") {
                try {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(port))
                    // Очередь на случай пары лишних подключений (сканер портов и т.п.).
                    server = ss
                    ss.soTimeout = 1000
                    Log.i(TAG, "share server on :$port, token=$token")
                    var attempts = 0
                    while (!cancelled && !served) {
                        val socket = try {
                            ss.accept()
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        attempts += 1
                        if (attempts > 10) break // защита от мусорных подключений
                        handle(socket)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "share server failed: ${e.message}")
                    if (!cancelled && !served) {
                        main.post { onDone(false, e.message ?: "сервер не поднялся") }
                    }
                } finally {
                    runCatching { server?.close() }
                    server = null
                }
            }
        }

        private fun handle(socket: Socket) {
            socket.use { sock ->
                sock.soTimeout = 10_000
                val reader = sock.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                Log.d(TAG, "share request: ${requestLine.take(80)}")
                // Читаем заголовки до пустой строки (запрос маленький, тело не ждём).
                var line: String?
                var contentLength = 0L
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank()) break
                    if (line!!.startsWith("Content-Length:")) {
                        contentLength = line!!.substringAfter(':').trim().toLongOrNull() ?: 0L
                    }
                }
                if (contentLength in 1 until MAX_COPY_BYTES) {
                    // Кто-то прислал тело - прочесть и выбросить, чтобы ответ ушёл.
                    val sink = ByteArray(8192)
                    var left = contentLength
                    val input = sock.getInputStream()
                    while (left > 0) {
                        val n = input.read(sink, 0, minOf(sink.size.toLong(), left).toInt())
                        if (n < 0) break
                        left -= n
                    }
                }
                val wanted = requestLine.split(" ").getOrNull(1)?.trim('/') ?: ""
                if (wanted != token) {
                    sock.getOutputStream().write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    return
                }
                val file = provideFile()
                if (file == null || !file.isFile) {
                    sock.getOutputStream().write(
                        "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    main.post { onDone(false, "копия не готова") }
                    served = true
                    return
                }
                val bytes = file.readBytes()
                val head = ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
                sock.getOutputStream().apply {
                    write(head)
                    write(bytes)
                    flush()
                }
                served = true
                Log.i(TAG, "copy served: ${bytes.size} байт")
                main.post { onDone(true, null) }
            }
        }

        fun stop() {
            cancelled = true
            runCatching { server?.close() }
        }
    }

    // ── Приёмник: забрать копию напрямую ────────────────────────────────────

    /** Скачать копию с [host]:[port]/[token] в [dest]. true - скачалось. */
    fun pull(host: String, port: Int, token: String, dest: File): Boolean {
        val conn = (URL("http://$host:$port/$token").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 120_000
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "pull got $code")
                return false
            }
            val input = conn.inputStream
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            tmp.outputStream().use { out ->
                val sink = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(sink)
                    if (n < 0) break
                    total += n
                    if (total > MAX_COPY_BYTES) {
                        tmp.delete()
                        Log.w(TAG, "pull too big")
                        return false
                    }
                    out.write(sink, 0, n)
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return false
            }
            Log.i(TAG, "pulled copy: ${dest.length()} байт с $host:$port")
            return true
        } catch (e: IOException) {
            Log.w(TAG, "pull failed: ${e.message}")
            return false
        } finally {
            conn.disconnect()
        }
    }
}
