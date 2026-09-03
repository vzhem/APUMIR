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
// Постоянная примета человека - его @имя: он выбирает его один раз, оно
// разлетается роевой рассылкой и переустановку переживает. Поэтому именно
// @имя связывает старую запись с новой.
//
// Правила намеренно осторожные:
//  - настоящее имя подменяет только заглушку, имя от владельца не трогаем;
//  - двойники схлопываются лишь при совпадении @имени, догадок по похожести
//    нет;
//  - переписка не удаляется, а переносится на живую запись.
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
            // Заглушку заменяем настоящим именем; имя, данное владельцем вручную,
            // остаётся как есть.
            if (contactRepository.isPlaceholderName(contact.displayName)) {
                contactRepository.updateDisplayName(contact.id, clean)
                chatRepository.updateContactName(ownerId, clean)
                Log.i(TAG, "контакт $ownerId переименован в @$clean")
            }
            if (!contact.username.equals(clean, ignoreCase = true)) {
                contactRepository.updateUsername(contact.id, clean)
            }
        }

        // Двойники: те же @имя, но узел прежней установки. Переписку переносим
        // на живой узел, мёртвую запись убираем.
        val stale = contactRepository.findStaleTwins(clean, ownerId)
        for (oldId in stale) {
            if (contact != null) {
                chatRepository.absorbChatOf(oldId, ownerId)
            }
            contactRepository.deleteContact(oldId)
            Log.i(TAG, "двойник $oldId склеен с $ownerId по @$clean")
        }

        // Дубли чатов с одним и тем же узлом - следствие того, что чат
        // создаётся сразу в нескольких местах. Трогаем базу, только если этот
        // человек нам вообще знаком: для чужих имён из роя работы нет.
        if (contact != null || stale.isNotEmpty()) {
            chatRepository.mergeDuplicateChats(ownerId)
        }
    }
}
