package com.vladimir.messenger.data.profile

/**
 * Дата, как её объявил сам владелец: число, месяц и когда он это объявил.
 *
 * Года здесь нет намеренно: по сети он не едет, а для поздравления не нужен.
 */
data class PeerBirthday(
    val day: Int,
    val month: Int,
    val updatedAtMs: Long,
)

/**
 * Даты рождения друзей, пришедшие роевой рассылкой: `узел → дата`.
 *
 * Чистая книга без Android и без JSON: разбор и правила слияния проверяются
 * обычным JVM-тестом, а хранилище (prefs) лишь вызывает эти функции. Формат
 * строки — четыре поля через табуляцию, по строке на человека.
 *
 * Дата приходит только от самого владельца и только если он разрешил её
 * показывать. Отзыв (`день = 0, месяц = 0`) означает «скрыл»: запись исчезает,
 * иначе старая дата осталась бы на телефонах друзей навсегда.
 */
object BirthdayBook {
    /** Сколько чужих дат держим: больше друзей, чем у человека бывает. */
    const val MAX_ENTRIES = 256

    /** Объявление «дата скрыта»: и день, и месяц нулевые. */
    fun isRevoke(day: Int, month: Int): Boolean = day == 0 && month == 0

    fun encode(entries: Map<String, PeerBirthday>): String =
        entries.entries
            .sortedBy { it.key }
            .take(MAX_ENTRIES)
            .joinToString("\n") { (node, value) -> "$node\t${value.day}\t${value.month}\t${value.updatedAtMs}" }

    /** Разбор строгий: битая или чужая строка молча пропускается. */
    fun decode(text: String?): Map<String, PeerBirthday> {
        if (text.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, PeerBirthday>()
        for (line in text.lineSequence()) {
            if (result.size >= MAX_ENTRIES) break
            val parts = line.split('\t')
            if (parts.size != 4) continue
            val node = parts[0].trim().lowercase()
            if (node.isEmpty() || node.length > 256) continue
            val day = parts[1].toIntOrNull() ?: continue
            val month = parts[2].toIntOrNull() ?: continue
            val at = parts[3].toLongOrNull() ?: continue
            if (!Birthday.isValid(day, month) || at < 0L) continue
            result[node] = PeerBirthday(day, month, at)
        }
        return result
    }

    /**
     * Объявление от владельца узла.
     *
     * Старое объявление (метка времени меньше уже известной) не затирает новое:
     * пакеты ходят по сети вразнобой, и «позавчерашняя» дата не должна
     * возвращаться. Равные метки считаются повтором — запись не меняется.
     */
    fun apply(
        entries: Map<String, PeerBirthday>,
        nodeId: String,
        day: Int,
        month: Int,
        updatedAtMs: Long,
        nowMs: Long,
    ): Map<String, PeerBirthday> {
        val node = nodeId.trim().lowercase()
        if (node.isEmpty() || node.length > 256) return entries
        if (updatedAtMs < 0L) return entries
        val known = entries[node]
        if (known != null && updatedAtMs <= known.updatedAtMs) return entries
        if (isRevoke(day, month) || !Birthday.isValid(day, month)) {
            if (known == null) return entries
            return entries - node
        }
        val next = LinkedHashMap(entries)
        next[node] = PeerBirthday(day, month, updatedAtMs)
        if (next.size <= MAX_ENTRIES) return next
        // Ведём себя как очередь: самая старая по метке запись уходит первой,
        // только что записанную не вытесняем.
        val victim = next.entries
            .filter { it.key != node }
            .minByOrNull { it.value.updatedAtMs }
            ?.key
        return if (victim == null) next else next - victim
    }
}

/**
 * Кто сегодня празднует: правило показа пузыря вверху ленты.
 *
 * Показываем только тех, чья дата пришла нам сама (значит, владелец её не
 * скрыл), кого мы знаем как контакт, и кого человек не скрыл нажатием «×»
 * сегодня. Чистая функция — проверяется без телефона.
 */
object BirthdayBanner {
    data class Guest(val nodeId: String, val name: String)

    /**
     * Ключ «скрыл до конца дня».
     *
     * Год в ключе обязателен: в следующем году тот же человек должен появиться
     * снова, а не остаться скрытым навсегда.
     */
    fun dismissKey(nodeId: String, today: java.time.LocalDate): String =
        "${nodeId.trim().lowercase()}|${today.year}-${today.monthValue}-${today.dayOfMonth}"

    /**
     * Порядок поздравления: сначала по имени, потом по узлу — так список не
     * переставляется между кадрами, если даты пришли вразнобой.
     */
    fun guests(
        birthdays: Map<String, PeerBirthday>,
        contacts: List<Pair<String, String>>,
        today: java.time.LocalDate,
        dismissed: Set<String>,
    ): List<Guest> {
        if (birthdays.isEmpty() || contacts.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<Guest>()
        for ((rawId, rawName) in contacts) {
            val node = rawId.trim().lowercase()
            if (node.isEmpty() || !seen.add(node)) continue
            val value = birthdays[node] ?: continue
            if (!Birthday.isToday(value.day, value.month, today)) continue
            if (dismissKey(node, today) in dismissed) continue
            result += Guest(node, rawName.ifBlank { "?" })
        }
        return result.sortedWith(
            compareBy({ it.name.lowercase() }, { it.nodeId }),
        )
    }
}
