package com.vladimir.messenger.service

import android.util.Log
import com.vladimir.messenger.data.diagnostics.OperationalCounter
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics

/**
 * Разбор одного входящего служебного пакета под защитной обёрткой.
 *
 * Правило маршрутизации: сбой одного роутера не должен уносить с собой всё
 * остальное — обычное письмо обязано доехать до чата и уведомления. Но
 * «не упасть» и «промолчать» — разные вещи: раньше в `CoreServerService`
 * стоял `runCatching { ... }.getOrDefault(false)`, и упавший парсер оставлял
 * после себя ноль следов (docs/AUDIT_2026-10-26.md, п. 4.2). Теперь причина
 * попадает и в лог, и в счётчик `ROUTER_FAILED`.
 *
 * Живёт отдельным файлом, а не в `CoreServerService`: тот уже за потолком
 * god-файла, и новый код туда не пишется (правило 8 в `AGENTS.md`).
 *
 * `CancellationException` пробрасывается: `runCatching` ловил и его, из-за чего
 * отмена сервиса посреди разбора пакета терялась.
 *
 * Функция `inline`, чтобы её можно было вызывать из приостанавливающих
 * (`suspend`) роутеров — так же, как это делал `runCatching`.
 */
internal inline fun routePacket(tag: String, name: String, block: () -> Boolean): Boolean =
    try {
        block()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (e: Exception) {
        Log.w(tag, "router $name failed: ${e.javaClass.simpleName}: ${e.message}")
        OperationalDiagnostics.count(OperationalCounter.ROUTER_FAILED)
        false
    }
