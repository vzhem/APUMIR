package com.vladimir.messenger.ui.update

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Одноразовый замок одного нажатия «Скачать».
 *
 * Кнопка должна замолкать в том же кадре, в котором палец её коснулся, ещё до
 * сетевого запроса и до первого куска файла. [tryAcquire] атомарен: из десяти
 * быстрых тапов только один запускает работу, остальные ничего не делают.
 * Отпускается после исхода запроса; дальнейшую защиту держит состояние самой
 * загрузки (официальный DownloadManager или просьба к соседям).
 */
class UpdateDownloadTapGate {

    private val locked = AtomicBoolean(false)

    val isLocked: Boolean get() = locked.get()

    fun tryAcquire(): Boolean = locked.compareAndSet(false, true)

    fun release() {
        locked.set(false)
    }
}
