package com.vladimir.messenger.data.heartgame

import com.vladimir.messenger.data.referral.ReferralReceipt
import com.vladimir.messenger.data.swarm.Ed25519
import java.util.Base64

/** Forwardable signed self-reports, NOT proof that an unmodified client earned the points. */
object HeartGameWire {
    const val PREFIX = "APUTYP1|APUGAME1|"
    const val MAX_PACKET = 8192
    const val MAX_RECORDS = 5
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    const val FUTURE_MS = 5L * 60 * 1000
    data class Entry(val nodeId: String, val name: String, val score: Long, val updated: Long, val record: String)
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
                    else Entry(view.nodeId, name, score, time, record)
                }
            }
        }
    } catch (_: Exception) { null }

    fun newer(old: Entry?, next: Entry) = old == null || (next.updated > old.updated && next.score >= old.score)
    fun ranked(entries: Collection<Entry>) = entries.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.nodeId })
}
