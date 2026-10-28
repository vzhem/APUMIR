package com.vladimir.messenger.data.heartgame

import com.vladimir.messenger.data.referral.ReferralReceipt
import com.vladimir.messenger.data.swarm.Ed25519
import java.util.Base64
import java.security.MessageDigest

/** Forwardable signed self-reports, NOT proof that an unmodified client earned the points. */
object HeartGameWire {
    const val PREFIX = "APUTYP1|APUGAME1|"

    /** Запрос «пришли свой счёт»: APUGAMEREQ1|<узел-запросивший>|<метка_времени_ms>. Запрос не подписан:
     *  в ответ уходит только ПОДПИСАННАЯ запись счёта, а узел сверяется с фактическим отправителем. */
    const val REQUEST_PREFIX = "APUTYP1|APUGAMEREQ1|"

    fun buildRequest(nodeId: String, atMs: Long): String = "$REQUEST_PREFIX$nodeId|$atMs"

    /** Узел, попросивший прислать счёт, или null. Метка времени не старше 10 минут
     *  и не из будущего дальше 5 минут — защита от переигрывания старых пакетов. */
    fun parseRequest(text: String, nowMs: Long): String? {
        if (!text.startsWith(REQUEST_PREFIX) || text.length > 256) return null
        val parts = text.removePrefix(REQUEST_PREFIX).split('|')
        if (parts.size != 2) return null
        val node = parts[0]
        val at = parts[1].toLongOrNull() ?: return null
        if (node.isBlank() || node.length > 128 || node.any { it.isISOControl() }) return null
        if (at <= 0 || at < nowMs - 10 * 60 * 1000 || at > nowMs + FUTURE_MS) return null
        return node
    }
    const val MAX_PACKET = 8192
    const val MAX_RECORDS = 5
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    const val FUTURE_MS = 5L * 60 * 1000
    data class Entry(val nodeId: String, val name: String, val score: Long, val updated: Long, val record: String, val keyId: String)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private fun body(binding: ByteArray, score: Long, updated: Long, name: String) =
        "${encoder.encodeToString(binding)}|$score|$updated|${encoder.encodeToString(name.toByteArray(Charsets.UTF_8))}"
    fun sign(binding: ByteArray, seed: ByteArray, score: Long, updated: Long, name: String): String {
        require(score in 0..HeartGamePolicy.MAX_SCORE && name.length <= 48 && name.none { it.isISOControl() })
        val text = body(binding, score, updated, name)
        val signature = Ed25519.sign(Ed25519.keyPairFromSeed(seed).privateKey, (PREFIX + text).toByteArray())
        return "$text|${encoder.encodeToString(signature)}"
    }
    fun keyId(binding: ByteArray): String? {
        val view = ReferralReceipt.parseIdentityBinding(binding) ?: return null
        val offset = 3 + view.nodeId.length
        return MessageDigest.getInstance("SHA-256").digest(binding.copyOfRange(offset, offset + 32))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun verify(record: String, now: Long, bindingVerifier: (ByteArray) -> Boolean): Entry? = try {
        if (record.length > 1200) null else {
            val p = record.split('|')
            if (p.size != 5) null else {
                val score = p[1].toLongOrNull()
                val time = p[2].toLongOrNull()
                val binding = decoder.decode(p[0])
                val name = String(decoder.decode(p[3]), Charsets.UTF_8)
                val view = ReferralReceipt.parseIdentityBinding(binding)
                if (view == null || score == null || score !in 0..HeartGamePolicy.MAX_SCORE ||
                    time == null || time <= 0 || time < now - RETENTION_MS || time > now + FUTURE_MS ||
                    name.length > 48 || name.any { it.isISOControl() } || !bindingVerifier(binding)) null
                else {
                    val keyOffset = 3 + view.nodeId.length
                    val key = binding.copyOfRange(keyOffset, keyOffset + 32)
                    val signature = decoder.decode(p[4])
                    val canonical = body(binding, score, time, name)
                    if (record.substringBeforeLast('|') != canonical ||
                        !Ed25519.verify(key, (PREFIX + canonical).toByteArray(), signature)) null
                    else Entry(view.nodeId, name, score, time, record, requireNotNull(keyId(binding)))
                }
            }
        }
    } catch (_: Exception) { null }

    fun newer(old: Entry?, next: Entry) = old == null || (old.keyId == next.keyId && next.updated > old.updated && next.score >= old.score)

    /**
     * Место в таблице лидеров, начиная с 1.
     *
     * [own] может быть null: подписанная привязка личности есть не на каждом
     * телефоне (её установка идёт вместе с ядром), а место в таблице от подписи
     * не зависит. Без своей строки место считается по счёту - [ownScore]
     * сравнивается с уже известными результатами. При равенстве счёта место не
     * приписывается: считаются только строго большие результаты, поэтому число
     * получается не хуже настоящего, но и не выдуманным.
     */
    fun placeOf(rows: Collection<Entry>, ownScore: Long, own: Entry?): Int {
        if (own != null) {
            val ranked = ranked(rows.filter { it.keyId != own.keyId } + own)
            val index = ranked.indexOfFirst { it.keyId == own.keyId }
            if (index >= 0) return index + 1
        }
        return 1 + rows.count { it.score > ownScore }
    }
    fun ranked(entries: Collection<Entry>) = entries.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.keyId })
}
