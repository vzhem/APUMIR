package com.vladimir.messenger.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * «Этот телефон сейчас молчит» — состояние для человека, а не для отчёта.
 *
 * Зачем. Пока локальный ключ не устанавливается до старта ядра, приложение
 * честно не отправляет личное открытым текстом и не вскрывает входящее. Но
 * видно это было только в «Логах»: человек видел тишину — истории сутками ждут
 * получателя, сообщения не уходят, плашки нет. Сводки владельца 2026-10-15
 * показали ровно это: `signing_seed_keystore=unavailable`,
 * `signing_mode_reason=unwrap_failed`, все пакеты историй отложены, при этом на
 * экране ничего не говорило, что телефон не может говорить.
 *
 * Здесь нет ни ключей, ни идентификаторов: только причина словами и что с ней
 * делать. Состояние ставится один раз — когда ядро уже запущено и известно, чем
 * кончилась установка ключей: ядро запоминает свою identity на старте и позже
 * её не пересматривает.
 */
object IdentityHealth {
    /**
     * Почему телефон молчит.
     *
     * [READY] и [NO_IDENTITY] человеку показывать нечего: в первом случае всё
     * работает, во втором личность ещё не создана — это первый запуск, а не
     * поломка.
     */
    enum class Reason {
        /** Ещё не сообщали (ядро не стартовало): молчание ни о чём не говорит. */
        UNKNOWN,

        /** Подпись и ключ обмена встали до старта ядра — личное ходит. */
        READY,

        /** Личность ещё не создана: обычный первый запуск. */
        NO_IDENTITY,

        /**
         * Личность есть, но её локального ключа нет или он не открывается:
         * сброшенный Keystore, чужой блоб, перенос данных на другой телефон.
         * Само не починится — нужен профиль из резервной копии.
         */
        UNREADABLE,

        /**
         * Ключ не успел открыться за отведённое время при запуске ядра. Ядро
         * работает без него до перезапуска приложения.
         */
        TIMEOUT,

        /**
         * Подпись встала, а ключ обмена файлами - нет: личность на месте,
         * поэтому «личное не уходит» здесь было бы неправдой. Другой совет
         * человеку: личные конверты и файлы могут не открываться.
         */
        EXCHANGE_UNAVAILABLE,
    }

    data class Snapshot(
        val reason: Reason = Reason.UNKNOWN,
        /** Слово из отчёта: как ставилась подпись до старта ядра. Не для человека. */
        val signingInstall: String = "",
        /** Слово из отчёта: как ставился ключ обмена до старта ядра. */
        val exchangeInstall: String = "",
    )

    private val _state = MutableStateFlow(Snapshot())

    /** Для экранов: подписка на состояние, без опроса Keystore на каждый кадр. */
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * Что сказать человеку: null — ничего.
     *
     * Чистая функция от снимка, поэтому её проверяет обычный JVM-тест.
     */
    fun notice(snapshot: Snapshot): Reason? = when (snapshot.reason) {
        Reason.UNREADABLE, Reason.TIMEOUT, Reason.EXCHANGE_UNAVAILABLE -> snapshot.reason
        else -> null
    }

    /** Вызывается один раз при запуске ядра, когда известны обе установки. */
    fun noteBoot(signingInstall: String, exchangeInstall: String) {
        _state.value = Snapshot(
            reason = classify(signingInstall, exchangeInstall),
            signingInstall = signingInstall,
            exchangeInstall = exchangeInstall,
        )
    }

    /**
     * Чистое правило: по строкам установки — причина.
     *
     * Строки ровно те, что пишет `CoreServerService` в отчёт
     * (`signing_install_before_core` / `file_exchange_install_before_core`).
     */
    fun classify(signingInstall: String, exchangeInstall: String): Reason = when {
        signingInstall == "skipped_no_identity" -> Reason.NO_IDENTITY
        signingInstall == "ready" && exchangeInstall == "ready" -> Reason.READY
        signingInstall == "timeout" || exchangeInstall == "timeout" -> Reason.TIMEOUT
        // Подпись готова, обмен - нет. Раньше это попадало в UNREADABLE, и
        // человек читал «ключ этого телефона не читается» про рабочий ключ
        // личности: диагноз был не тот, а совет - восстанавливать профиль -
        // дороже, чем нужно.
        signingInstall == "ready" && exchangeInstall == "unavailable" -> Reason.EXCHANGE_UNAVAILABLE
        else -> Reason.UNREADABLE
    }

    /** Для тестов и для настроек: сбросить состояние между проверками. */
    internal fun resetForTests() {
        _state.value = Snapshot()
    }
}
