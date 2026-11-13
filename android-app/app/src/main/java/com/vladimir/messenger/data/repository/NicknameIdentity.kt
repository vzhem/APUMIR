package com.vladimir.messenger.data.repository

// =============================================================================
// NICKNAMEIDENTITY.KT — «это тот же самый человек»
// =============================================================================
// Узел приложения (node_id) живёт ровно до переустановки: поставил заново -
// стал в глазах сети новым человеком. Собеседник видел набор букв и цифр
// «Contact a1b2c3d4», а прежняя запись оставалась висеть рядом мёртвым
// двойником, из-за чего в списке чатов было по два одинаковых собеседника,
// а сообщения уходили на мёртвый адрес.
//
// Совпадение @имени — только способ найти кандидатов, не подтверждение
// личности. Автоматическая склейка допустима лишь при подтверждённой связи
// записей с одним устройством и наличии адресата для переноса истории.
//
// Правила намеренно осторожные:
//  - настоящее имя подменяет только заглушку, имя от владельца не трогаем;
//  - совпадение имени без проверки устройства не объединяет контакты;
//  - переписка переносится до удаления старой записи.
// =============================================================================

import android.util.Log

object NicknameIdentity {

    private const val TAG = "NicknameIdentity"

    /**
     * Уже применённые пары «узел - @имя».
     *
     * Пакеты с именами ходят по рою эпидемией: каждый телефон пересказывает их
     * соседям, поэтому одно и то же имя прилетает снова и снова. Без этой
     * памяти каждый такой пакет запускал обход базы и запись в неё, список
     * чатов пересобирался без остановки и приложение переставало отвечать.
     * Повтор теперь стоит одну проверку в памяти.
     */
    private val applied = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Применить узнанное @имя к контактам.
     *
     * @param ownerId узел, который объявил это имя (живой).
     * @param nickname @имя без собаки.
     */
    suspend fun apply(
        ownerId: String,
        nickname: String,
        contactRepository: ContactRepository,
        chatRepository: ChatRepository,
    ) {
        val clean = nickname.trim().trimStart('@').trim()
        if (ownerId.isBlank() || clean.isEmpty()) return

        // Тот же узел с тем же именем уже разобран - выходим сразу.
        // Ключ в нижнем регистре: «Server5» и «server5» - одно имя.
        if (!applied.add(ownerId + "|" + clean.lowercase())) return

        val contact = contactRepository.getContactByFingerprint(ownerId)
        if (contact != null) {
            // @никнейм кладём в СВОЁ поле - его показывает профиль собеседника.
            if (!contact.username.equals(clean, ignoreCase = true)) {
                contactRepository.updateUsername(contact.id, clean)
            }
            // Видимым именем ник становится только пока настоящего нет вовсе.
            // Раньше он подменял заглушку всегда, а presence следом возвращал
            // настоящее имя - строка в списке чатов мигала между ними.
            if (contactRepository.isPlaceholderName(contact.displayName)) {
                contactRepository.updateDisplayName(contact.id, clean)
                Log.i(TAG, "контакт $ownerId временно показан как @$clean")
            }
        } else {
            // Контакта нет, а чат может уже быть: он заводится при первом
            // входящем сообщении отдельно от списка контактов. Без этой ветки
            // такая переписка навсегда оставалась под техническим именем,
            // и на двух экранах один человек выглядел как два разных.
            val chat = chatRepository.getChatByContactId(ownerId)
            if (chat != null && contactRepository.isPlaceholderName(chat.contactName)) {
                chatRepository.updateContactName(ownerId, clean)
                Log.i(TAG, "чат с $ownerId переименован в @$clean (контакта нет)")
            }
        }

        // Without an existing destination contact, a nickname alone cannot
        // justify deleting the old contact or its history.
        if (contact != null) {
            val stale = contactRepository.findStaleTwins(clean, ownerId).filter { it != contact.id }
            if (stale.isNotEmpty()) {
                chatRepository.getOrCreateChat(ownerId, contact.displayName)
                for (oldId in stale) {
                    chatRepository.absorbChatOf(oldId, ownerId)
                    contactRepository.deleteContact(oldId)
                    Log.i(TAG, "подтверждённый двойник $oldId склеен с $ownerId")
                }
            }
            chatRepository.mergeDuplicateChats(ownerId)
        }
    }
}
