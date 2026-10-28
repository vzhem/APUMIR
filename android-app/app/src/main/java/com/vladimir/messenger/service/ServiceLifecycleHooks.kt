package com.vladimir.messenger.service

import android.content.Context

/**
 * Что делать, когда служба доставки уходит.
 *
 * Вынесено из `CoreServerService` (тот за потолком god-файла, AGENTS.md п. 8),
 * но по смыслу это его часть: служба знает, **почему** остановилась, и только
 * она может отличить «меня убили» от «я сам перезапускаюсь».
 *
 * Три случая, и путать их нельзя:
 *
 *  - `planned = true` — служба остановилась сама и знает причину (нет
 *    личности, ядро не поднялось, перезапуск зеркала). Быстрый подъём здесь
 *    вреден: причина не исчезнет за две минуты, и телефон будил бы себя по
 *    кругу. Ставится редкая неточная страховка раз в час.
 *  - `planned = false` — процесс ушёл (смахнули приложение, система убила
 *    службу без `onDestroy`). Это ровно тот случай, ради которого владелец
 *    просил «как в Telegram»: подъём через две минуты, а не через час.
 *  - `onTaskRemoved` — человек смахнул приложение из списка задач. Служба
 *    может прожить ещё несколько секунд, но система её почти наверняка
 *    добьёт, поэтому будильник назначается сразу.
 */
internal object ServiceLifecycleHooks {

    /** Служба уничтожена: [planned] — остановилась сама, а не её убили. */
    fun onDestroyed(context: Context, planned: Boolean) {
        runCatching { EmergencyKeepAlive.onServiceStopped(context, intentional = planned) }
    }

    /** Приложение убрали из списка задач: быстрый подъём. */
    fun onTaskRemoved(context: Context) {
        runCatching { EmergencyKeepAlive.onTaskRemoved(context) }
    }
}
