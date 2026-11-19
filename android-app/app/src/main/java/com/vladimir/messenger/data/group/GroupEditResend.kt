package com.vladimir.messenger.data.group

import android.util.Log

/**
 * Правка сообщения группы, которая не доехала до части участников: очередь
 * на повтор и сама досылка.
 *
 * Веер ([GroupDelivery.deliver]) отправляет конверт каждому один раз и
 * возвращает список недоступных. Раньше этот список терялся: телефон,
 * лежавший в офлайне в момент правки, навсегда оставался со старым текстом.
 * Владелец потребовал прямо: «исправил сообщение внутри — оно должно везде
 * исправиться».
 *
 * Здесь — только проводка поверх [GroupEditRetry] (чистая очередь, покрыта
 * JVM-тестом). Файл отдельный, чтобы не растить GroupRepository: это god-файл
 * с потолком строк (scripts/ci/check-god-files.py).
 */
object GroupEditResend {

    /** Очередь общая на процесс: правка одна, а зовут её из многих мест. */
    private val retry = GroupEditRetry()

    /** Запомнить, кому правка не доехала, чтобы дослать при появлении узла. */
    fun remember(groupId: String, envelope: String, failed: List<String>, editedAtMs: Long) =
        retry.remember(groupId, envelope, failed, editedAtMs)

    /**
     * Узел появился в сети — дослать ему правку, которая раньше не дошла.
     *
     * Повтор безопасен: получатель применяет правку только если она свежее
     * уже применённой (метка editedAtMs), поэтому дубликат ничего не ломает.
     */
    suspend fun onPeerOnline(
        nodeId: String,
        deliver: suspend (groupId: String, envelope: String, peers: List<String>) -> DeliveryReport,
    ) {
        val attempt = retry.take(nodeId) ?: return
        // Id конверта считается от группы, собеседника и текста (GroupsModule),
        // поэтому повтор — это то же сообщение для ядра: send_message для уже
        // удержанного не молчит, а предлагает его заново (MESH origin: offering again).
        val report = runCatching {
            deliver(attempt.groupId, attempt.envelope, listOf(nodeId))
        }.getOrNull()
        if (report != null && report.isFullSuccess) {
            retry.delivered(nodeId, attempt)
            Log.i(TAG, "undelivered edit re-sent group=${attempt.groupId} peer=${nodeId.takeLast(8)}")
        } else {
            retry.failed(nodeId, attempt)
        }
    }

    /** Сколько узлов ждёт досылки (диагностика и тесты). */
    fun pendingCount(): Int = retry.size()

    companion object {
        private const val TAG = "GroupEditResend"
    }
}
