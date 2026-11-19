package com.vladimir.messenger.data.group

/**
 * Повторная досылка правок сообщений группы тем участникам, кому пакет не
 * доехал с первого раза.
 *
 * Зачем. Веер правки ([GroupDelivery.deliver]) отправляет конверт каждому
 * участнику один раз и возвращает список недоступных. Раньше этот список
 * просто терялся: телефон, лежавший в офлайне в момент правки, навсегда
 * оставался со старым текстом, хотя автор уже видел исправленный. Владелец
 * сформулировал требование прямо: «исправил сообщение внутри — оно должно
 * везде исправиться».
 *
 * Что здесь есть:
 *  - одна запись на узел (более свежая правка заменяет прежнюю);
 *  - защита от частых повторов по одному узлу ([RETRY_INTERVAL_MS]);
 *  - удаление записи после успешной доставки.
 *
 * Класс намеренно не знает ни о Room, ни об Android: это чистая логика,
 * которую проверяет JVM-тест `GroupEditRetryTest`. Отправку делает
 * вызывающая сторона ([take] возвращает то, что пора дослать).
 */
class GroupEditRetry(
    /** Часы: подменяются в тестах, чтобы не ждать реальную минуту. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Недоставленная правка одному участнику. */
    data class Pending(
        val groupId: String,
        val envelope: String,
        /** Метка правки: по ней более свежая запись заменяет прежнюю. */
        val editedAtMs: Long,
        /** Когда последний раз пытались дослать. */
        val lastAttemptMs: Long,
    )

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    /** Сколько узлов ждёт досылки. */
    fun size(): Int = pending.size

    /** Ждёт ли досылки конкретный узел (для тестов и диагностики). */
    fun pendingFor(nodeId: String): Pending? = pending[nodeId]

    /**
     * Запомнить, что правка [envelope] не доехала до [failed].
     *
     * Более свежая правка вытесняет прежнюю: досылать всё равно нужно
     * последний текст. Пустые узлы и пустой список игнорируются.
     */
    fun remember(groupId: String, envelope: String, failed: List<String>, editedAtMs: Long) {
        if (failed.isEmpty() || envelope.isBlank()) return
        val now = clock()
        for (peerId in failed) {
            if (peerId.isBlank()) continue
            val previous = pending[peerId]
            if (previous != null && previous.editedAtMs > editedAtMs) continue
            pending[peerId] = Pending(groupId, envelope, editedAtMs, now)
        }
    }

    /**
     * Что пора дослать этому узлу, или null.
     *
     * Частая причина вызова — событие присутствия: узел только что появился,
     * и такие события идут пачками. Чаще раза в [RETRY_INTERVAL_MS] на узел
     * не возвращаем ничего, чтобы не забивать веер.
     */
    fun take(nodeId: String): Pending? {
        if (nodeId.isBlank()) return null
        val entry = pending[nodeId] ?: return null
        val now = clock()
        if (now - entry.lastAttemptMs < RETRY_INTERVAL_MS) return null
        // Попытку сразу кладём в очередь: отсчёт частоты начинается здесь,
        // а [delivered] сравнивает запись по значению и потому не стирает
        // более свежую правку, пришедшую за время отправки.
        val attempt = entry.copy(lastAttemptMs = now)
        pending[nodeId] = attempt
        return attempt
    }

    /**
     * Досылка удалась — забыть запись.
     *
     * Удаление условное: если за время отправки появилась более свежая
     * правка тому же узлу, она должна остаться в очереди.
     */
    fun delivered(nodeId: String, attempt: Pending) {
        pending.remove(nodeId, attempt)
    }

    /**
     * Досылка не удалась — отметить попытку, оставив запись на потом.
     *
     * Тоже условно: если за время отправки пришла более свежая правка, она
     * важнее неудачной попытки и не должна быть затёрта старой.
     */
    fun failed(nodeId: String, attempt: Pending) {
        val current = pending[nodeId]
        if (current == null || current.editedAtMs <= attempt.editedAtMs) pending[nodeId] = attempt
    }

    /** Полная очистка (выход из аккаунта, тесты). */
    fun clear() = pending.clear()

    companion object {
        /**
         * Не чаще раза в минуту на узел: присутствие шумит, а получатель
         * применяет правку только по свежести метки — спешить некуда.
         */
        const val RETRY_INTERVAL_MS = 60_000L
    }
}
