package com.vladimir.messenger.data.file

import java.util.Base64

/**
 * Формат UDP-канала файловой передачи (docs/SWARM_MOBILE.md): датаграммы,
 * сигналы обмена кандидатами и сборка фрагментов. Чистый Kotlin без
 * Android — JVM-тестируется.
 *
 * Датаграммы (магика [MAGIC], ≤ [MAX_DGRAM_BYTES] байт — в MTU без IP-
 * фрагментации):
 *  - **проба** (пробивание NAT): `seen` + подписанная привязка ключа
 *    обмена отправителя; получатель проверяет подпись и узел;
 *  - **данные**: часть base64-текста `apu-file1|…` (тот же фрагмент, что
 *    по LAN и по брокеру); текст режется на части ≤ [MAX_PART_BYTES].
 *
 * Содержимое данных не шифруется на этом слое: само оно уже зашифровано
 * ключом файла (тот же текст, что ходит по LAN), а канал связывается с
 * подписанными сигналами через [sessionTag].
 */
object FileUdpWire {

    const val MAGIC = "APUUDP01"
    const val KIND_PROBE: Byte = 0
    const val KIND_DATA: Byte = 1

    /** Потолок датаграммы: 1231 байт влезает в обычный MTU без фрагментации. */
    const val MAX_DGRAM_BYTES = 1231
    /** Потолок части текста в одной датаграмме (остальное — заголовок). */
    const val MAX_PART_BYTES = 1160
    /** Максимальный размер подписанной привязки в пробе (как у `fwant`). */
    const val MAX_BINDING_BYTES = 512
    /** Тег UDP-сессии: 12 байт, объявляется в сигналах. */
    const val SESSION_TAG_BYTES = 12

    // ── Сигналы (через брокера, не запечатываются — как APULAN1) ───────────
    //
    // APUUDP1|ufseek|b64(привязка)|ip:port;ip:port|b64(sessionTag)
    // APUUDP1|ufcand|b64(привязка)|ip:port;ip:port|b64(sessionTag)

    const val SIGNAL_PREFIX = "APUUDP1"
    const val KIND_UFSEEK = "ufseek"
    const val KIND_UFCAND = "ufcand"
    const val MAX_CANDIDATES = 6
    private const val MAX_SIGNAL_CHARS = 4 * 1024

    data class UdpSignal(
        val kind: String,
        val binding: ByteArray,
        val endpoints: List<Pair<String, Int>>,
        val sessionTag: ByteArray,
    )

    fun isUdpSignalText(text: String): Boolean =
        text.length <= MAX_SIGNAL_CHARS && text.startsWith("$SIGNAL_PREFIX|")

    fun buildUdpSignal(
        kind: String,
        binding: ByteArray,
        endpoints: List<Pair<String, Int>>,
        sessionTag: ByteArray,
    ): String {
        require(kind == KIND_UFSEEK || kind == KIND_UFCAND) { "bad udp signal kind" }
        require(binding.isNotEmpty() && binding.size <= MAX_BINDING_BYTES) { "bad binding size" }
        require(endpoints.isNotEmpty() && endpoints.size <= MAX_CANDIDATES) { "bad candidate list" }
        val body = endpoints.joinToString(";") { (ip, port) ->
            require(com.vladimir.messenger.data.call.CallWire.isValidIpLiteral(ip)) { "bad candidate address" }
            require(port in 1..65535) { "bad candidate port" }
            "$ip:$port"
        }
        val tag = Base64.getUrlEncoder().withoutPadding().encodeToString(sessionTag)
        return "$SIGNAL_PREFIX|$kind|${Base64.getUrlEncoder().withoutPadding().encodeToString(binding)}|$body|$tag"
    }

    /** Разобрать сигнал; null — не наш формат или битый (тихо отбрасываем). */
    fun parseUdpSignal(text: String): UdpSignal? {
        if (!isUdpSignalText(text)) return null
        val parts = text.split("|")
        if (parts.size != 5) return null
        if (parts[1] != KIND_UFSEEK && parts[1] != KIND_UFCAND) return null
        val binding = try {
            Base64.getUrlDecoder().decode(parts[2])
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (binding.isEmpty() || binding.size > MAX_BINDING_BYTES) return null
        val endpoints = parseEndpointList(parts[3])
        if (endpoints == null || endpoints.isEmpty() || endpoints.size > MAX_CANDIDATES) return null
        val sessionTag = try {
            Base64.getUrlDecoder().decode(parts[4])
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (sessionTag.size != SESSION_TAG_BYTES) return null
        return UdpSignal(parts[1], binding, endpoints, sessionTag)
    }

    /** `ip:port;ip:port;…` → пары; null — любой битый литерал (IPv6 разрешён). */
    private fun parseEndpointList(body: String): List<Pair<String, Int>>? {
        val list = ArrayList<Pair<String, Int>>()
        for (cell in body.split(";")) {
            val idx = cell.lastIndexOf(':')
            if (idx <= 0) return null
            val ip = cell.substring(0, idx)
            val port = cell.substring(idx + 1).toIntOrNull() ?: return null
            if (!com.vladimir.messenger.data.call.CallWire.isValidIpLiteral(ip) || port !in 1..65535) {
                return null
            }
            list += ip to port
        }
        if (list.isEmpty()) return null
        return list
    }

    // ── Датаграммы ──────────────────────────────────────────────────────────

    /**
     * Проба: `MAGIC | kind=0 | seen:1 | bindingLen:2(BE) | binding`.
     * seen = 1, если мы уже получали пробы собеседника (оба направления
     * открыты — адрес можно фиксировать).
     */
    fun encodeProbe(seen: Boolean, binding: ByteArray): ByteArray {
        require(binding.isNotEmpty() && binding.size <= MAX_BINDING_BYTES) { "bad probe binding" }
        return buildProbeInternal(seen, binding)
    }

    private fun buildProbeInternal(seen: Boolean, binding: ByteArray): ByteArray {
        val out = ByteArray(MAGIC.length + 1 + 1 + 2 + binding.size)
        MAGIC.toByteArray().copyInto(out)
        out[MAGIC.length] = KIND_PROBE
        out[MAGIC.length + 1] = if (seen) 1 else 0
        out[MAGIC.length + 2] = (binding.size shr 8).toByte()
        out[MAGIC.length + 3] = (binding.size and 0xFF).toByte()
        binding.copyInto(out, MAGIC.length + 4)
        return out
    }

    data class Probe(val seen: Boolean, val binding: ByteArray)

    /** Разобрать пробу; null — не наша датаграмма (STUN/шум/чужой магик). */
    fun parseProbe(bytes: ByteArray): Probe? {
        val magicLen = MAGIC.length
        if (bytes.size < magicLen + 5) return null
        for (i in 0 until magicLen) if (bytes[i] != MAGIC.toByteArray()[i]) return null
        if (bytes[magicLen] != KIND_PROBE) return null
        val seen = bytes[magicLen + 1] == 1.toByte()
        val len = ((bytes[magicLen + 2].toInt() and 0xFF) shl 8) or (bytes[magicLen + 3].toInt() and 0xFF)
        if (len == 0 || len > MAX_BINDING_BYTES) return null
        if (bytes.size != magicLen + 4 + len) return null
        return Probe(seen, bytes.copyOfRange(magicLen + 4, bytes.size))
    }

    /**
     * Данные: `MAGIC | kind=1 | sessionTag(12) | fragId:4(BE) | seq:2(BE) |
     * cnt:2(BE) | partLen:2(BE) | part`. [text] — целый фрагмент
     * `apu-file1|…`; режется на части ≤ [MAX_PART_BYTES].
     */
    fun splitForDatagrams(text: String, sessionTag: ByteArray, fragId: Long): List<ByteArray> {
        require(sessionTag.size == SESSION_TAG_BYTES) { "bad session tag" }
        require(fragId in 0L..0xFFFFFFFFL) { "bad fragment id" }
        val payload = text.toByteArray(Charsets.ISO_8859_1)
        val count = (payload.size + MAX_PART_BYTES - 1) / MAX_PART_BYTES
        require(count in 1..0x10000) { "fragment too large for udp" }
        val out = ArrayList<ByteArray>(count)
        for (seq in 0 until count) {
            val start = seq * MAX_PART_BYTES
            val end = minOf(start + MAX_PART_BYTES, payload.size)
            out += buildDataPart(sessionTag, fragId, seq.toLong(), count.toLong(), payload.copyOfRange(start, end))
        }
        return out
    }

    private fun buildDataPart(
        sessionTag: ByteArray,
        fragId: Long,
        seq: Long,
        count: Long,
        part: ByteArray,
    ): ByteArray {
        require(part.size <= MAX_PART_BYTES) { "udp part too large" }
        val out = ByteArray(MAGIC.length + 1 + SESSION_TAG_BYTES + 4 + 2 + 2 + 2 + part.size)
        MAGIC.toByteArray().copyInto(out)
        out[MAGIC.length] = KIND_DATA
        sessionTag.copyInto(out, MAGIC.length + 1)
        val id = fragId.toInt()
        out[MAGIC.length + 1 + SESSION_TAG_BYTES] = (id ushr 24).toByte()
        out[MAGIC.length + 2 + SESSION_TAG_BYTES] = (id ushr 16).toByte()
        out[MAGIC.length + 3 + SESSION_TAG_BYTES] = (id ushr 8).toByte()
        out[MAGIC.length + 4 + SESSION_TAG_BYTES] = (id and 0xFF).toByte()
        out[MAGIC.length + 5 + SESSION_TAG_BYTES] = (seq.toInt() ushr 8).toByte()
        out[MAGIC.length + 6 + SESSION_TAG_BYTES] = (seq.toInt() and 0xFF).toByte()
        out[MAGIC.length + 7 + SESSION_TAG_BYTES] = (count.toInt() ushr 8).toByte()
        out[MAGIC.length + 8 + SESSION_TAG_BYTES] = (count.toInt() and 0xFF).toByte()
        out[MAGIC.length + 9 + SESSION_TAG_BYTES] = (part.size ushr 8).toByte()
        out[MAGIC.length + 10 + SESSION_TAG_BYTES] = (part.size and 0xFF).toByte()
        part.copyInto(out, MAGIC.length + 11 + SESSION_TAG_BYTES)
        return out
    }

    data class DataPart(
        val sessionTag: ByteArray,
        val fragId: Long,
        val seq: Long,
        val count: Long,
        val part: ByteArray,
    )

    private val magicBytes: ByteArray by lazy { MAGIC.toByteArray() }

    /** Разобрать данные; null — не наша датаграмма. */
    fun parseDataPart(bytes: ByteArray): DataPart? {
        val headerLen = MAGIC.length + 1 + SESSION_TAG_BYTES + 10
        if (bytes.size < headerLen + 1) return null
        for (i in 0 until MAGIC.length) if (bytes[i] != magicBytes[i]) return null
        if (bytes[MAGIC.length] != KIND_DATA) return null
        val base = MAGIC.length
        val sessionTag = bytes.copyOfRange(base + 1, base + 1 + SESSION_TAG_BYTES)
        val fragId = ((bytes[base + 13].toInt() and 0xFF) shl 24) or
            ((bytes[base + 14].toInt() and 0xFF) shl 16) or
            ((bytes[base + 15].toInt() and 0xFF) shl 8) or
            (bytes[base + 16].toInt() and 0xFF)
        val fragIdU = fragId.toUInt().toLong()
        val seq = ((bytes[base + 17].toInt() and 0xFF) shl 8) or (bytes[base + 18].toInt() and 0xFF)
        val count = ((bytes[base + 19].toInt() and 0xFF) shl 8) or (bytes[base + 20].toInt() and 0xFF)
        val partLen = ((bytes[base + 21].toInt() and 0xFF) shl 8) or (bytes[base + 22].toInt() and 0xFF)
        if (count == 0 || seq >= count) return null
        if (partLen > MAX_PART_BYTES) return null
        if (bytes.size != headerLen + partLen) return null
        return DataPart(sessionTag, fragIdU, seq.toLong(), count.toLong(), bytes.copyOfRange(headerLen, bytes.size))
    }

    /**
     * Сборка фрагмента из частей: `put` возвращает готовый текст, когда все
     * части на месте; части с чужими fragId не мешают (LRU + TTL).
     */
    class Reassembler(private val nowMs: () -> Long = System::currentTimeMillis) {
        private class Pending(val expected: Long, val createdAtMs: Long, val parts: MutableMap<Long, ByteArray>)

        private val pending = LinkedHashMap<Long, Pending>()
        private val maxPending = 64
        private val pendingTtlMs = 10_000L

        /** null — фрагмент ещё не собран; строка — собранный текст. */
        @Synchronized
        fun put(part: DataPart): String? {
            val now = nowMs()
            pending.entries.removeIf { it.value.createdAtMs + pendingTtlMs < now }
            while (pending.size > maxPending) {
                pending.remove(pending.keys.first())
            }
            val item = pending.getOrPut(part.fragId) {
                if (part.count > 0x10000) return null
                Pending(part.count, now, LinkedHashMap())
            }
            val expected = item.expected
            if (part.count != expected) return null
            if (part.seq in 0L until expected) {
                item.parts[part.seq] = part.part
            }
            if (item.parts.size == expected.toInt()) {
                pending.remove(part.fragId)
                val total = item.parts.values.sumOf { it.size }
                val buf = ByteArray(total)
                var offset = 0
                for (seq in 0L until expected) {
                    val p = item.parts[seq] ?: return null
                    p.copyInto(buf, offset)
                    offset += p.size
                }
                return String(buf, Charsets.ISO_8859_1)
            }
            return null
        }
    }

    /** Тексты фрагмента `apu-file1|…` не должны ломаться при резе: только ASCII. */
    fun isSplittableText(text: String): Boolean =
        text.isNotEmpty() &&
            text.length <= MAX_DGRAM_BYTES * 0x10000 &&
            text.all { it.code < 0x100 }
}
