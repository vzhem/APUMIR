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
            val table = table(rows.filter { it.keyId != own.keyId } + own, own.keyId)
            val index = table.indexOfFirst { it.entry.keyId == own.keyId }
            if (index >= 0) return index + 1
        }
        return 1 + table(rows, null).count { it.entry.score > ownScore }
    }
    fun ranked(entries: Collection<Entry>) = entries.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.keyId })

    /** Строка таблицы: результат и сколько записей склеено под этим именем. */
    data class Row(val entry: Entry, val merged: Int)

    /**
     * Таблица лидеров: одна строка на участника.
     *
     * У одного человека в сети бывает несколько ключей подписи. Ключ привязан к
     * устройству (Android Keystore), поэтому телефон и компьютер дают разные
     * keyId, а после переустановки появляется ещё один. Подписи при этом
     * настоящие, и по ним не видно, что это один и тот же человек: общего у
     * записей только имя, выбранное самим человеком. Без склейки человек
     * занимал в таблице две строки подряд - владелец, скриншот 28.10.2026:
     * «В таблице один и тот же участник задвоился».
     *
     * Склейка идёт в два шага. Сначала по узлу (`nodeId`): у одного человека с
     * телефоном и компьютером узел общий, а ключи подписи разные - это точное
     * совпадение, и именно оно убирает вторую строку «я со старого ключа».
     * Затем по имени (без учёта регистра и крайних пробелов): новый узел при
     * переустановке с прежним именем. Остаётся лучший результат. Своя строка
     * всегда остаётся своей, даже если у другого ключа с тем же именем счёт
     * выше: иначе в таблице было бы одно число, а в шапке другое.
     *
     * Пустое имя ничего не склеивает между разными узлами: под ним стоят
     * разные люди, которые просто не назвались.
     *
     * Чего эта функция НЕ делает: не подтверждает, что имя принадлежит
     * одному человеку. Имя выбирает сам клиент, поэтому два разных человека
     * с одинаковым именем попадут в одну строку, а чужое имя можно взять
     * себе. Это плата за таблицу, собранную из самостоятельно подписанных
     * отчётов, а не из серверной базы; текст подсказки в окне говорит об
     * этом прямо.
     *
     * @param ownKey keyId своей строки, если подпись личности доступна.
     */
    fun table(entries: Collection<Entry>, ownKey: String?): List<Row> {
        // Шаг 1: одна личность - один узел. У человека с двумя устройствами
        // (телефон и компьютер) узел общий, а ключи подписи разные: ключ
        // привязан к устройству. Поэтому склейка по узлу - точная, её и делаем
        // первой.
        val byNode = LinkedHashMap<String, MutableList<Entry>>()
        for (entry in entries) {
            byNode.getOrPut("node:" + entry.nodeId.trim().lowercase(java.util.Locale.ROOT)) {
                mutableListOf()
            }.add(entry)
        }
        // Шаг 2: разные узлы, но одно имя - тот же человек после переустановки
        // или после смены личности (новый узел, прежнее имя). Пустое имя ничего
        // не склеивает: под ним стоят разные люди.
        val groups = LinkedHashMap<String, MutableList<Entry>>()
        for (group in byNode.values) {
            groups.getOrPut(mergeKey(group)) { mutableListOf() }.addAll(group)
        }
        return groups.values
            .map { group ->
                // Лучший результат группы: больший счёт, при равенстве - более
                // свежий, при полном равенстве - меньший keyId (чтобы порядок не
                // «прыгал» между телефонами). Своя строка всегда своя.
                val best = group.firstOrNull { it.keyId == ownKey }
                    ?: group.maxWith(compareBy<Entry> { it.score }.thenBy { it.updated }.thenByDescending { it.keyId })
                Row(best, group.size)
            }
            .sortedWith(compareByDescending<Row> { it.entry.score }.thenBy { it.entry.keyId })
    }

    /**
     * Имя участника для показа: подписанное имя, иначе имя из адресной книги,
     * иначе короткое обозначение узла.
     *
     * Почему третья ступень обязательна. У записи с результатом всегда есть
     * узел и подпись; имени может не быть, если человек его не задавал (старая
     * установка, сброс профиля). Решение владельца 29.10.2026: «если есть
     * количество сердечек, то и ник должен быть с этими данными. Без имени
     * вообще такого не должно быть». Поэтому вместо пустоты показывается
     * короткое обозначение узла («pk_1a2b3c…9f0e») - это настоящий
     * идентификатор участника, а не выдуманный ник, и по нему видно, что
     * человек просто не назвался.
     *
     * @param contactName имя из адресной книги этого телефона, если известно.
     */
    fun displayName(entry: Entry, contactName: String?): String {
        val signed = entry.name.trim()
        if (signed.isNotEmpty()) return signed
        val known = contactName?.trim().orEmpty()
        if (known.isNotEmpty()) return known
        return shortNodeName(entry.nodeId, entry.keyId)
    }

    /** Короткое обозначение узла: «pk_1a2b3c…9f0e». Пустым не бывает. */
    fun shortNodeName(nodeId: String, keyId: String = ""): String {
        val body = nodeId.trim().removePrefix("pk_").ifEmpty { keyId.trim() }
        if (body.isEmpty()) return "pk_"
        return if (body.length <= 12) "pk_" + body else "pk_" + body.take(6) + "…" + body.takeLast(4)
    }

    /** Ключ склейки: имя без регистра; без имени - свой узел, ни с кем не вместе. */
    private fun mergeKey(group: List<Entry>): String {
        val name = group.firstNotNullOfOrNull { entry ->
            entry.name.trim().lowercase(java.util.Locale.ROOT).takeIf { it.isNotEmpty() }
        }
        if (name != null) return "name:" + name
        val node = group.first().nodeId.trim().lowercase(java.util.Locale.ROOT)
        return if (node.isEmpty()) "key:" + group.first().keyId else "node:" + node
    }
}
