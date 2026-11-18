package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.diagnostics.PushRegisterReason
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PushTokenRegistrarTest {
    private val peerA = "pk_" + "2".repeat(32)
    private val peerB = "pk_" + "3".repeat(32)

    private fun peer(value: String) = PushTargets.Peer("pk_" + "1".repeat(32), value, 1)

    private class Harness(
        var enabledValue: Boolean = true,
        var provider: PushFirebaseSignals.Outcome = PushFirebaseSignals.Outcome.Token("fcm-test-token-abcdefghijklmnop"),
        var journal: FcmTokenJournal.State = FcmTokenJournal.State.Missing,
        var targets: Pair<PushTargets.Outcome, List<PushTargets.Peer>> = PushTargets.Outcome.COMPLETED to emptyList(),
        var registerOutcome: (String) -> PushRegistrationRunner.Outcome = { PushRegistrationRunner.Outcome.Registered },
        var revokeOutcome: (String) -> Boolean = { true },
        var calls: MutableList<String> = mutableListOf(),
        var failures: Int = 0,
        var peerReason: PushRegisterReason = PushRegisterReason.PEER_NOT_READY,
        val reasons: MutableList<PushRegisterReason> = mutableListOf(),
    ) {
        fun build() = PushTokenRegistrar(
            enabled = { enabledValue },
            token = { provider },
            coordinator = { register, record ->
                PushRuntimeCoordinator(
                    gate = object : PushRuntimeCoordinator.Gate {
                        override fun enabled() = enabledValue
                    },
                    journal = object : PushRuntimeCoordinator.TokenJournal {
                        override fun record(token: String, nowMs: Long) = journal.also { calls += "journal" }
                        override fun forget(): Boolean = true
                        override fun confirmRegistered(token: String): Boolean = true
                    },
                    register = register,
                    enqueueCheck = { record() },
                    recordFailure = { failures++ },
                    // Координатор называет свой шаг (отказ журнала токена) прямо
                    // в отчёт; без этой связи харнесс его не видел и проверка
                    // причины проходила вслепую.
                    recordReason = { reason -> reasons += reason },
                )
            },
            peers = { targets },
            register = { peerId, token ->
                calls += "register:${peerId.takeLast(4)}:${token.length}"
                registerOutcome(peerId)
            },
            revoke = { peerId -> calls += "revoke"; revokeOutcome(peerId) },
            forgetToken = { calls += "forget"; true },
            nowMs = { 1_700_000_000_000 },
            recordFailure = { failures++ },
            recordReason = { reason -> reasons += reason },
            reasonSink = { peerReason },
        )
    }

    @Test fun disabledOrUnconfiguredBuildsTouchNothing() = runBlocking {
        val harness = Harness(enabledValue = false)
        assertEquals(PushTokenRegistrar.Outcome.Disabled, harness.build().run())
        assertEquals(emptyList<String>(), harness.calls)

        val noServices = Harness(provider = PushFirebaseSignals.Outcome.NoPlayServices)
        assertEquals(PushTokenRegistrar.Outcome.NoPlayServices, noServices.build().run())
        assertEquals(emptyList<String>(), noServices.calls)

        val notConfigured = Harness(provider = PushFirebaseSignals.Outcome.NotConfigured)
        assertEquals(PushTokenRegistrar.Outcome.NotConfigured, notConfigured.build().run())
        assertEquals(emptyList<String>(), notConfigured.calls)
    }

    @Test fun anUnchangedTokenMakesNoNetworkCall() = runBlocking {
        val harness = Harness(journal = FcmTokenJournal.State.Unchanged, targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA)))
        assertEquals(PushTokenRegistrar.Outcome.AlreadyCurrent, harness.build().run())
        assertEquals(listOf("journal"), harness.calls)
    }

    @Test fun everyLivePeerIsRegisteredWithTheSameToken() = runBlocking {
        val harness = Harness(targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)))
        assertEquals(PushTokenRegistrar.Outcome.Registered, harness.build().run())
        assertEquals(3, harness.calls.size)
        assertEquals("journal", harness.calls.first())
        assertEquals(2, harness.calls.count { it.startsWith("register:") })
    }

    @Test fun anUnreadableIndexOrAnEmptyOneIsNeverReportedAsRegistered() = runBlocking {
        val partial = Harness(targets = PushTargets.Outcome.RETRY to listOf(peer(peerA)))
        assertEquals(PushTokenRegistrar.Outcome.Failed, partial.build().run())
        assertEquals(0, partial.calls.count { it.startsWith("register:") })

        // Пустой список — не отказ сервера: регистрировать было некого.
        val none = Harness(targets = PushTargets.Outcome.COMPLETED to emptyList())
        assertEquals(PushTokenRegistrar.Outcome.NoTargets, none.build().run())
    }

    @Test fun oneRefusalFailsTheAttemptInsteadOfPretendingSuccess() = runBlocking {
        val harness = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)),
            registerOutcome = { if (it == peerB) PushRegistrationRunner.Outcome.Failed else PushRegistrationRunner.Outcome.Registered },
        )
        assertEquals(PushTokenRegistrar.Outcome.Failed, harness.build().run())
        // The coordinator turns a failed registration into Failed and forgets the token.
        assertEquals(1, harness.calls.count { it == "journal" })
    }

    @Test fun aPeerWithoutAUsableRecordIsNotCountedButDoesNotBlockOthers() = runBlocking {
        // Nothing was registered, so nothing may be reported as registered.
        val onlyNotReady = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA)),
            registerOutcome = { PushRegistrationRunner.Outcome.NotReady },
        )
        assertEquals(PushTokenRegistrar.Outcome.Failed, onlyNotReady.build().run())

        // One real registration next to an unready peer is still a registration.
        val mixed = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)),
            registerOutcome = {
                if (it == peerB) PushRegistrationRunner.Outcome.NotReady else PushRegistrationRunner.Outcome.Registered
            },
        )
        assertEquals(PushTokenRegistrar.Outcome.Registered, mixed.build().run())
    }

    @Test fun forgetRevokesEveryLiveRoute() = runBlocking {
        val harness = Harness(targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)))
        assertEquals(true, harness.build().forget())
        assertEquals(2, harness.calls.count { it == "revoke" })
        assertEquals(listOf("forget", "revoke", "revoke"), harness.calls)

        val oneFailed = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)),
            revokeOutcome = { it == peerA },
        )
        assertEquals(false, oneFailed.build().forget())

        val unreadable = Harness(targets = PushTargets.Outcome.RETRY to emptyList())
        assertEquals(false, unreadable.build().forget())
    }

    @Test fun cancellationFromTheProviderIsNotConvertedIntoAnOutcome() = runBlocking {
        val harness = Harness()
        val registrar = PushTokenRegistrar(
            enabled = { true },
            token = { throw CancellationException("cancelled") },
            coordinator = { _, _ -> error("no coordinator for a cancelled token request") },
            peers = { PushTargets.Outcome.COMPLETED to emptyList() },
            register = { _, _ -> PushRegistrationRunner.Outcome.Registered },
            revoke = { false },
        )
        var cancelled = false
        try {
            registrar.run()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertEquals(true, cancelled)
        assertEquals(emptyList<String>(), harness.calls)
    }

    @Test fun aPassThatRegisteredNobodyNamesTheStepThatStopped() {
        // Отчёт с телефона показывал только «фаза=failed», и по нему нельзя было
        // отличить отказ службы от незакреплённой пары. Причина обязана дойти.
        val harness = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)),
            registerOutcome = { PushRegistrationRunner.Outcome.NotReady },
            peerReason = PushRegisterReason.PEER_NOT_READY,
        )
        runBlocking { assertEquals(PushTokenRegistrar.Outcome.Failed, harness.build().run()) }
        // Отчёт читает ПОСЛЕДНЕЕ записанное значение: перезапись грубой
        // догадкой стирает ту самую причину, ради которой строка добавлена.
        assertEquals(
            "причина пары не дожила до отчёта: ${harness.reasons}",
            PushRegisterReason.PEER_NOT_READY,
            harness.reasons.lastOrNull(),
        )
    }

    @Test fun anUnreadablePeerSetIsNotMistakenForAnEmptyOne() {
        // Пустой набор — это NO_TARGETS, непрочитанный — FAILED. Раньше оба
        // выглядели как «failed», и причина «нет закреплённых» терялась.
        val unreadable = Harness(
            targets = PushTargets.Outcome.RETRY to emptyList(),
            peerReason = PushRegisterReason.PEER_NOT_READY,
        )
        runBlocking { assertEquals(PushTokenRegistrar.Outcome.Failed, unreadable.build().run()) }
        assertEquals(
            "непрочитанный список назван пустым: ${unreadable.reasons}",
            PushRegisterReason.TARGETS_UNREADABLE,
            unreadable.reasons.lastOrNull(),
        )

        val empty = Harness(targets = PushTargets.Outcome.COMPLETED to emptyList())
        runBlocking { assertEquals(PushTokenRegistrar.Outcome.NoTargets, empty.build().run()) }
        assertEquals(
            "пустой набор не назван своим именем: ${empty.reasons}",
            PushRegisterReason.NO_PINNED_PEERS,
            empty.reasons.lastOrNull(),
        )
    }

    @Test fun aRegisteredPassRecordsSuccessNotAFailure() {
        val harness = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA)),
            registerOutcome = { PushRegistrationRunner.Outcome.Registered },
        )
        runBlocking { assertEquals(PushTokenRegistrar.Outcome.Registered, harness.build().run()) }
        assertEquals(listOf(PushRegisterReason.REGISTERED), harness.reasons)
    }

    @Test fun aRefusedOperationKeepsTheStepThatStopped() {
        // Ровно тот путь, которым на телефоне закончилась последняя попытка:
        // токен получен, журнал записан, операция для пары не прошла. Раннер
        // назвал шаг, но регистратор возвращался досрочно и затирал его
        // «причина не записана» — и отчёт снова показывал три болезни сразу.
        val harness = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)),
            registerOutcome = { PushRegistrationRunner.Outcome.Failed },
            peerReason = PushRegisterReason.SERVER_UNAVAILABLE,
        )
        runBlocking { assertEquals(PushTokenRegistrar.Outcome.Failed, harness.build().run()) }
        // Первый же отказ обрывает проход: до второй пары он не доходит.
        assertEquals(1, harness.calls.count { it.startsWith("register:") })
        assertEquals(
            "шаг, на котором остановилась операция, затёрт: ${harness.reasons}",
            PushRegisterReason.SERVER_UNAVAILABLE,
            harness.reasons.lastOrNull(),
        )
    }

    @Test fun anUnavailableTokenJournalNamesItself() {
        // Журнал отказал токену: до перебора пар дело не дошло, и назвать шаг
        // больше некому. Без этой строки причина оставалась от прошлого прохода.
        val harness = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA)),
            journal = FcmTokenJournal.State.Unavailable,
        )
        runBlocking { assertEquals(PushTokenRegistrar.Outcome.Failed, harness.build().run()) }
        assertEquals(0, harness.calls.count { it.startsWith("register:") })
        assertEquals(
            "отказ журнала не назван: ${harness.reasons}",
            PushRegisterReason.JOURNAL_UNAVAILABLE,
            harness.reasons.lastOrNull(),
        )
    }
}
