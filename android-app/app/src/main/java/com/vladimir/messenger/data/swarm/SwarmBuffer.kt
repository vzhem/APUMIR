package com.vladimir.messenger.data.swarm

/**
 * Короткая память роя: то, что пришло раньше времени и ждёт недостающего.
 *
 * Кусок поста, дошедший раньше манифеста, и манифест, дошедший раньше ключа
 * автора, не отбрасываются, а лежат здесь до [ttlMs] миллисекунд. Больше
 * [capacity] записей не держим - самая старая выпадает: буфер не должен
 * стать способом занять память телефона мусором с чужого узла.
 *
 * Чистый Kotlin, без Android: проверяется JVM-тестом.
 */
class SwarmBuffer<T>(
    private val capacity: Int,
    private val ttlMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry<T>(val atMs: Long, val value: T)

    private val entries = LinkedHashMap<String, Entry<T>>()

    /** Положить под ключом; повторный ключ заменяет запись и обновляет её время. */
    @Synchronized
    fun put(key: String, value: T) {
        evictExpired()
        entries.remove(key)
        while (entries.size >= capacity) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
        entries[key] = Entry(clock(), value)
    }

    /** Забрать (и убрать) всё, что подходит под условие, в порядке поступления. */
    @Synchronized
    fun take(predicate: (T) -> Boolean): List<T> {
        evictExpired()
        val out = ArrayList<T>()
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.value.value)) {
                out.add(e.value.value)
                it.remove()
            }
        }
        return out
    }

    @Synchronized
    fun contains(key: String): Boolean {
        evictExpired()
        return entries.containsKey(key)
    }

    @Synchronized
    fun size(): Int {
        evictExpired()
        return entries.size
    }

    private fun evictExpired() {
        val now = clock()
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.atMs > ttlMs) it.remove() else break
        }
    }
}
