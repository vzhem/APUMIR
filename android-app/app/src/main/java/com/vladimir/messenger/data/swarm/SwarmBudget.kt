package com.vladimir.messenger.data.swarm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Полоса бюджета.
 *
 * Содержимое (сообщения, посты, куски фото, карточки сообществ) при пустом
 * бюджете ЖДЁТ своей очереди - терять его нельзя. Служебные пакеты (просмотры,
 * реакции) при пустом бюджете НЕ уходят: они не стоят того, чтобы задерживать
 * содержимое, а их отсутствие лишь чуть занижает счётчик у соседей.
 */
enum class SwarmLane { CONTENT, SIGNAL }

/**
 * Один бюджет исходящих групповых пакетов на весь процесс.
 *
 * Ведро с жетонами: пополняется непрерывно со скоростью «предел в минуту»,
 * вмещает не больше одного предела - значит, после тишины короткая пачка
 * (пост в группу на 30 человек) уходит сразу, а длинная (пост с шестью фото
 * в группу на 200) растягивается до разрешённого темпа, а не забивает
 * очередь ядра (у неё 256 мест, лишнее молча теряется).
 *
 * Пределы читаются при каждом решении ([limits]), поэтому смена режима в
 * настройках или переход на мобильный интернет действуют сразу. Часы и сон
 * подменяемы - так бюджет проверяется JVM-тестом без реального ожидания.
 */
class SwarmBudget(
    private val limits: () -> SwarmLimits,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var content = 0.0
    private var signal = 0.0
    private var lastRefillMs = 0L
    private var primed = false

    /** Дождаться жетона (содержимое) или забрать его, если он есть (служебное). */
    suspend fun acquire(lane: SwarmLane): Boolean {
        while (true) {
            // Отрицательное значение - решение принято без ожидания:
            // -1 = жетон взят, -2 = служебный пакет отброшен.
            val waitMs: Long = mutex.withLock {
                val current = limits()
                refill(clock(), current)
                when {
                    take(lane) -> TOKEN_TAKEN
                    lane == SwarmLane.SIGNAL -> SIGNAL_DROPPED
                    else -> millisUntilNextToken(current)
                }
            }
            when (waitMs) {
                TOKEN_TAKEN -> return true
                SIGNAL_DROPPED -> return false
                else -> sleep(waitMs)
            }
        }
    }

    /** Служебный пакет: жетон есть - забрали, нет - не ждём. */
    suspend fun tryAcquire(lane: SwarmLane): Boolean = mutex.withLock {
        refill(clock(), limits())
        take(lane)
    }

    /** Сколько жетонов свободно сейчас (для экрана настроек и тестов). */
    suspend fun available(lane: SwarmLane): Int = mutex.withLock {
        refill(clock(), limits())
        when (lane) {
            SwarmLane.CONTENT -> content
            SwarmLane.SIGNAL -> signal
        }.toInt()
    }

    private fun take(lane: SwarmLane): Boolean = when (lane) {
        SwarmLane.CONTENT -> if (content >= 1.0) {
            content -= 1.0
            true
        } else {
            false
        }
        SwarmLane.SIGNAL -> if (signal >= 1.0) {
            signal -= 1.0
            true
        } else {
            false
        }
    }

    private fun refill(now: Long, current: SwarmLimits) {
        val contentCap = current.maxPacketsPerMinute.coerceAtLeast(1).toDouble()
        val signalCap = current.maxSignalsPerMinute.coerceAtLeast(1).toDouble()
        if (!primed) {
            // Первый пакет после старта не должен ждать: ведро полное.
            content = contentCap
            signal = signalCap
            lastRefillMs = now
            primed = true
            return
        }
        val elapsed = (now - lastRefillMs).coerceAtLeast(0L)
        lastRefillMs = now
        content = (content + elapsed * contentCap / MINUTE_MS).coerceAtMost(contentCap)
        signal = (signal + elapsed * signalCap / MINUTE_MS).coerceAtMost(signalCap)
    }

    private fun millisUntilNextToken(current: SwarmLimits): Long {
        val perMinute = current.maxPacketsPerMinute.coerceAtLeast(1)
        val missing = (1.0 - content).coerceAtLeast(0.0)
        val ms = (missing * MINUTE_MS / perMinute).toLong() + 1
        return ms.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
    }

    companion object {
        private const val TOKEN_TAKEN = -1L
        private const val SIGNAL_DROPPED = -2L
        const val MINUTE_MS = 60_000.0
        /** Не крутиться впустую и не спать дольше, чем нужно для одного жетона. */
        const val MIN_WAIT_MS = 20L
        const val MAX_WAIT_MS = 5_000L
    }
}
