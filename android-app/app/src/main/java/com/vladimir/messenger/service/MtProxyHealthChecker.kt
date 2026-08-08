package com.vladimir.messenger.service

import android.util.Log
import com.vladimir.messenger.data.repository.MtProxyRepository
import com.vladimir.messenger.domain.model.MtProtoProxy
import com.vladimir.messenger.domain.model.ProxyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Проверка работоспособности MTProto прокси.
 * Использует простой TCP connect (без MTProto handshake) — этого достаточно
 * чтобы отсеять мёртвые хосты.
 */
@Singleton
class MtProxyHealthChecker @Inject constructor(
    private val repo: MtProxyRepository,
) {
    companion object {
        private const val TAG = "MtProxyHealthChecker"
        private const val TCP_TIMEOUT_MS = 5000
        private const val PARALLEL_CHECKS = 30
    }

    data class CheckResult(
        val proxy: MtProtoProxy,
        val success: Boolean,
        val latencyMs: Long,
        val error: String? = null,
    )

    /**
     * Проверить один прокси. Возвращает результат.
     */
    suspend fun checkOne(proxy: MtProtoProxy): CheckResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            when (proxy.type) {
                ProxyType.MTProto -> checkTcp(proxy.host, proxy.port)
                ProxyType.SOCKS5 -> checkSocks5(proxy.host, proxy.port, proxy.username, proxy.password)
                ProxyType.HTTP -> checkHttp(proxy.host, proxy.port, proxy.username, proxy.password)
            }
            val latency = System.currentTimeMillis() - start
            Log.i(TAG, "OK: ${proxy.type} ${proxy.host}:${proxy.port} (${latency}ms)")
            repo.markSuccess(proxy.id)
            CheckResult(proxy, success = true, latencyMs = latency)
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            val err = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "FAIL: ${proxy.type} ${proxy.host}:${proxy.port} — $err")
            repo.markFailed(proxy.id)
            CheckResult(proxy, success = false, latencyMs = latency, error = err)
        }
    }

    private fun checkTcp(host: String, port: Int) {
        Socket().use { socket ->
            socket.soTimeout = TCP_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
        }
    }

    private fun checkSocks5(host: String, port: Int, username: String, password: String) {
        Socket().use { socket ->
            socket.soTimeout = TCP_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // Greeting: VER=5, NMETHODS=1 или 2, METHODS
            if (username.isNotEmpty()) {
                output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))  // NO_AUTH + USERNAME_PASSWORD
            } else {
                output.write(byteArrayOf(0x05, 0x01, 0x00))  // NO_AUTH only
            }
            output.flush()

            val response = ByteArray(2)
            val read = input.read(response)
            if (read < 2 || response[0] != 0x05.toByte()) {
                throw Exception("SOCKS5: invalid greeting")
            }
            val method = response[1].toInt()
            if (method == 0xFF) throw Exception("SOCKS5: no acceptable auth method")

            // Если требуется auth (method = 0x02)
            if (method == 0x02) {
                val authPacket = mutableListOf<Byte>()
                authPacket.add(0x01)  // VER
                authPacket.add(username.length.toByte())
                authPacket.addAll(username.toByteArray().toList())
                authPacket.add(password.length.toByte())
                authPacket.addAll(password.toByteArray().toList())
                output.write(authPacket.toByteArray())
                output.flush()

                val authResp = ByteArray(2)
                val authRead = input.read(authResp)
                if (authRead < 2 || authResp[1] != 0x00.toByte()) {
                    throw Exception("SOCKS5: auth failed")
                }
            }

            // CONNECT request: VER=5, CMD=1, RSV=0, ATYP=1 (IPv4), addr, port
            // Для healthcheck достаточно что сервер принял CONNECT — не обязательно реально подключаться
            // Просто посылаем CONNECT на 127.0.0.1:1 (запрещённый, но проверим что сервер жив)
            output.write(byteArrayOf(
                0x05, 0x01, 0x00, 0x01,  // VER, CMD, RSV, ATYP (IPv4)
                127, 0, 0, 1,             // 127.0.0.1
                0x00, 0x01                // port 1
            ))
            output.flush()

            val connResp = ByteArray(10)
            val connRead = input.read(connResp)
            if (connRead < 2 || connResp[0] != 0x05.toByte()) {
                throw Exception("SOCKS5: invalid connect response")
            }
            // REP может быть != 0x00 (connection refused на 127.0.0.1:1 — это нормально)
            // Главное что сервер ответил — значит он жив
        }
    }

    private fun checkHttp(host: String, port: Int, username: String, password: String) {
        Socket().use { socket ->
            socket.soTimeout = TCP_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            val output = socket.getOutputStream()

            // HTTP CONNECT request
            val authHeader = if (username.isNotEmpty()) {
                val credentials = android.util.Base64.encodeToString(
                    "$username:$password".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                "Proxy-Authorization: Basic $credentials\r\n"
            } else ""

            val request = "CONNECT api.telegram.org:443 HTTP/1.1\r\n" +
                          "Host: api.telegram.org:443\r\n" +
                          authHeader +
                          "\r\n"
            output.write(request.toByteArray())
            output.flush()

            val input = socket.getInputStream()
            val buffer = ByteArray(1024)
            val read = input.read(buffer)
            val response = String(buffer, 0, read)
            if (!response.contains("200")) {
                throw Exception("HTTP proxy: ${response.split("\r\n")[0]}")
            }
        }
    }

    /**
     * Проверить прокси параллельно.
     * Проверяет топ-50: активный + недавние + с успешными проверками.
     */
    suspend fun checkAll(): List<CheckResult> = coroutineScope {
        val all = repo.getAll()
        
        // Выбираем топ-50 для проверки:
        // 1. Активный прокси
        // 2. Прокси с successCount > 0 (сортировка по successCount desc)
        // 3. Самые свежие (addedAt desc)
        val candidates = buildList {
            val active = all.firstOrNull { it.isActive }
            if (active != null) add(active)
            
            // Рабочие прокси
            val working = all
                .filter { it.successCount > 0 && it.id != active?.id }
                .sortedByDescending { it.successCount }
                .take(20)
            addAll(working)
            
            // Свежие (не проверенные)
            val recent = all
                .filter { it.successCount == 0 && it.id != active?.id && it.id !in working.map { w -> w.id } }
                .sortedByDescending { it.addedAt }
                .take(29)  // чтобы в сумме ~50
            addAll(recent)
        }.distinctBy { it.id }
        
        Log.i(TAG, "Starting healthcheck for ${candidates.size} proxies (of ${all.size} total)")

        val results = candidates
            .chunked(PARALLEL_CHECKS)
            .flatMap { chunk ->
                chunk.map { proxy -> async { checkOne(proxy) } }.awaitAll()
            }

        val ok = results.count { it.success }
        Log.i(TAG, "Healthcheck done: $ok/${results.size} OK")
        results
    }

    /**
     * Проверить прокси и выбрать лучший как активный.
     * Критерий: успешные + наименьшая latency.
     */
    suspend fun checkAllAndPickBest(): CheckResult? {
        val results = checkAll()
        val best = results
            .filter { it.success }
            .minByOrNull { it.latencyMs }

        if (best != null) {
            repo.setActive(best.proxy.id)
            Log.i(TAG, "Best proxy selected: ${best.proxy.host}:${best.proxy.port} (${best.latencyMs}ms)")
        } else {
            Log.w(TAG, "No working proxies found")
        }
        return best
    }
}
