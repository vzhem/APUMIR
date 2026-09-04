package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.entity.ContactEntity
import com.vladimir.messenger.domain.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(
    private val contactDao: ContactDao,
    private val chatRepository: ChatRepository,
) {
    fun observeContacts(): Flow<List<Contact>> =
        contactDao.observeAllContacts().map { it.map { e -> e.toDomain() } }

    suspend fun addContact(
        displayName: String,
        fingerprint: String,
        username: String = "",
    ): Result<Contact> {
        return try {
            val existing = contactDao.getContactByFingerprint(fingerprint)
            if (existing != null) {
                return Result.failure(Exception("Contact already exists"))
            }

            // IMPORTANT:
            // contact id == peer fingerprint/node id
            // so chat.contactId can be used as real recipient peer id
            val contactId = fingerprint

            RustBridge.addContact(contactId, displayName)

            val entity = ContactEntity(
                id = contactId,
                displayName = displayName,
                fingerprint = fingerprint,
                username = username,
            )

            contactDao.insertContact(entity)
            Result.success(entity.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getContactById(contactId: String): Contact? =
        contactDao.getContactById(contactId)?.toDomain()

    suspend fun getContactByFingerprint(fingerprint: String): Contact? =
        contactDao.getContactByFingerprint(fingerprint)?.toDomain()

    suspend fun updateOnlineStatus(contactId: String, isOnline: Boolean) {
        contactDao.updateOnlineStatus(contactId, isOnline)
    }

    suspend fun setAllOffline() = contactDao.setAllOffline()

    suspend fun updateDisplayName(contactId: String, name: String) {
        contactDao.updateDisplayName(contactId, name)
    }

    // updateUsername уже есть ниже, рядом с renameContact - второй такой же
    // метод не нужен.

    /**
     * Имя-заглушка вида «Contact a1b2c3d4»: такое можно молча заменить настоящим,
     * а данное владельцем вручную - нельзя.
     */
    fun isPlaceholderName(name: String): Boolean {
        val clean = name.trim()
        return clean.isEmpty() ||
            clean == "Anonymous" ||
            clean == "Unknown" ||
            clean == "Без имени" ||
            clean.startsWith("Contact ") ||
            looksTechnical(clean)
    }

    /**
     * Похоже ли имя на технический идентификатор, а не на имя человека.
     *
     * После обмена по QR в чат иногда попадал кусок node_id («pk_ae8962d8…»
     * или просто набор букв и цифр): чат создаётся сразу, а настоящее имя
     * приходит позже с presence. Такое имя не считалось заглушкой, и присланное
     * настоящее его уже не подменяло - на телефоне навсегда оставались
     * «буквыцифры».
     *
     * Признаём техническим: начинается с «pk_», либо это одно длинное слово из
     * латиницы и цифр, в котором есть и то и другое. Обычное имя так не
     * выглядит: в нём есть пробел, кириллица или нет цифр вовсе.
     */
    fun looksTechnical(name: String): Boolean {
        val clean = name.trim()
        if (clean.startsWith("pk_")) return true
        if (clean.length < 12 || clean.contains(' ')) return false
        if (!clean.all { it.isLetterOrDigit() }) return false
        // Только латиница: имя «Александр2000» техническим считать нельзя.
        if (clean.any { it.code > 127 }) return false
        return clean.any { it.isDigit() } && clean.any { it.isLetter() }
    }

    /** Настоящее ли это имя, то есть стоит ли им подменять заглушку. */
    fun isRealName(name: String?): Boolean =
        !name.isNullOrBlank() && !isPlaceholderName(name)

    /**
     * Склейка записей одной и той же трубки.
     *
     * Каждая переустановка приложения даёт новый node_id, и собеседник получал
     * ещё один контакт с тем же @именем - в списке висели дубли, а чат уходил
     * на мёртвый адрес. Связываем их по @имени: оно у человека одно и переживает
     * переустановку. Побеждает запись, о которой слышали позже.
     *
     * @return идентификаторы устаревших записей, которые вызывающий должен убрать
     *         вместе с их чатами.
     */
    suspend fun findStaleTwins(username: String, keepContactId: String): List<String> {
        val clean = username.trim().trimStart('@').trim()
        if (clean.isEmpty()) return emptyList()
        // Двойник ловится двумя способами: по @имени и по видимому имени.
        // Одного @имени мало - оно есть только у тех, от кого прилетал пакет с
        // именем, а записи из QR и первых сообщений остаются с пустым полем.
        // Именно поэтому «Server5» и «server5» не склеивались.
        val byUsername = contactDao.getContactsByUsername(clean)
        val byDisplayName = contactDao.getContactsByDisplayName(clean)
        return (byUsername + byDisplayName)
            .map { it.id }
            .distinct()
            .filter { it != keepContactId }
    }

    /**
     * Удаление контакта уносит и его чат.
     *
     * Раньше чат оставался: раздел «Контакты» и главная показывали разные
     * наборы людей, а осиротевший чат писал в пустоту.
     */
    suspend fun deleteContact(contactId: String) {
        val entity = contactDao.getContactById(contactId) ?: return
        contactDao.deleteContact(entity)
        runCatching { chatRepository.deleteChatsOf(contactId) }
    }

    /**
     * Свести «Контакты» и главную к одному набору людей: у каждого контакта
     * должен быть ровно один чат. Расхождение накапливалось само - чат
     * создавался не на всех путях добавления, а дубли не схлопывались.
     */
    suspend fun reconcileChats() {
        mergeDuplicateContacts()
        for (contact in contactDao.observeAllContactsOnce()) {
            runCatching {
                // Сначала смотрим, есть ли вообще что чинить. Прежняя версия
                // на каждом контакте звала getOrCreateChat и слияние, а каждая
                // запись в базу будит поток списка чатов: на десятках контактов
                // это давало шторм перерисовок и приложение переставало
                // отвечать. Теперь при исправном списке записей нет вовсе.
                val chats = chatRepository.chatCountOf(contact.id)
                when {
                    chats == 0 -> chatRepository.getOrCreateChat(contact.id, contact.displayName)
                    chats > 1 -> chatRepository.mergeDuplicateChats(contact.id)
                }
            }
        }
    }

    /**
     * Схлопнуть записи, отличающиеся только регистром имени.
     *
     * Склейка по прилетевшему @имени лечит будущее, но уже накопившихся
     * двойников - «Server5» и «server5» - не трогает: пакет с именем может и
     * не прийти, а пара так и висит в списке. Поэтому разово при запуске
     * сверяем список сами.
     *
     * Осторожность как в reconcileChats: сначала ЧИТАЕМ и ищем расхождение,
     * пишем только при настоящем дубле. Иначе сверка на каждом запуске снова
     * будила бы список чатов и вернула бы «Приложение не отвечает».
     */
    private suspend fun mergeDuplicateContacts() {
        val all = contactDao.observeAllContactsOnce()
        if (all.size < 2) return

        // Группируем по видимому имени без учёта регистра. Пустые имена
        // пропускаем: они не примета человека, а отсутствие приметы.
        val groups = all
            .filter { it.displayName.isBlank().not() && !isPlaceholderName(it.displayName) }
            .groupBy { it.displayName.trim().lowercase() }

        for ((_, twins) in groups) {
            if (twins.size < 2) continue

            // Побеждает запись с живым чатом и историей: терять переписку
            // нельзя. lastSeen хранится строкой и для сравнения не годится,
            // поэтому смотрим на то, что важно человеку - есть ли сообщения.
            val keep = twins.maxByOrNull { twin ->
                runCatching { chatRepository.chatCountOf(twin.id) }.getOrDefault(0)
            } ?: continue
            // Перенос истории возможен только в существующий чат, поэтому у
            // выжившей записи он должен быть. Если чата нет - создаём, иначе
            // переписка двойника осталась бы висеть сиротой.
            runCatching { chatRepository.getOrCreateChat(keep.id, keep.displayName) }

            for (dup in twins) {
                if (dup.id == keep.id) continue
                runCatching {
                    // Переписку не теряем - переносим на живую запись.
                    chatRepository.absorbChatOf(dup.id, keep.id)
                    // Подчищаем всё, что могло остаться от двойника: иначе в
                    // списке останется чат, пишущий на мёртвый адрес.
                    chatRepository.deleteChatsOf(dup.id)
                    contactDao.deleteContact(dup)
                    Log.i("ContactRepository", "двойник склеен по имени")
                }
            }
            runCatching { chatRepository.mergeDuplicateChats(keep.id) }
        }
    }

    private fun ContactEntity.toDomain() = Contact(
        id = id,
        displayName = displayName,
        fingerprint = fingerprint,
        isOnline = isOnline,
        lastSeen = lastSeen,
        username = username,
    )

    suspend fun updateUsername(contactId: String, username: String) {
        contactDao.updateUsername(contactId, username)
    }

    suspend fun renameContact(contactId: String, newName: String): Result<Unit> {
        return try {
            val existing = contactDao.getContactByFingerprint(contactId)
            if (existing == null) {
                return Result.failure(Exception("Contact not found"))
            }
            contactDao.updateDisplayName(existing.id, newName)
            chatRepository.updateContactName(contactId, newName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}