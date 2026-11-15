package com.vladimir.messenger.service

import com.vladimir.messenger.data.diagnostics.PresenceReportStrings
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.domain.model.Contact

/**
 * Контакт, которому принадлежит наблюдавшееся присутствие сосед.
 *
 * Зачем отдельный файл: `CoreServerService.kt` — god-файл под храповиком строк
 * (`scripts/ci/check-god-files.py`), поэтому разрешение наблюдения живёт здесь.
 *
 * Владелец 2026-11-15: «все телефоны включены, но показывает, что все друзья
 * вышли из сети». Смотрение только по отпечатку было одной из причин:
 * `ContactEntity.fingerprint` — поле со значением по умолчанию, и в строках,
 * заведённых не через `addContact` (импорт, зеркало, восстановление), оно
 * пустое или хранит другой идентификатор. Наблюдение тогда приходит, проходит
 * все проверки свежести — и не может быть записано: `PresenceRecovery.sync`
 * выходит, если контакт не найден. Сосед оказывается «в сети» в памяти ядра и
 * «не в сети» в базе одновременно, и экран показывает второе.
 *
 * Поэтому порядок такой: отпечаток, затем id — у контакта id и есть узел.
 */
internal suspend fun resolvePresenceContact(
    contactRepository: ContactRepository,
    peerId: String,
): Contact? {
    val byFingerprint = contactRepository.getContactByFingerprint(peerId)
    if (byFingerprint != null) return byFingerprint
    val byId = contactRepository.getContactById(peerId)
    if (byId != null) return byId
    // Не нашли — считаем. Без этого числа в отчёте нельзя отличить
    // «наблюдений не приходит» (ядро молчит) от «приходят, но записывать
    // некуда» (не тот контакт): у диагнозов разное лечение.
    TransferDiagnostics.count(PresenceReportStrings.PRESENCE_UNKNOWN_CONTACT)
    return null
}
