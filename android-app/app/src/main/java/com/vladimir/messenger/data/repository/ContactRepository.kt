package com.vladimir.messenger.data.repository

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
            clean.startsWith("Contact ")
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
        return contactDao.getContactsByUsername(clean)
            .map { it.id }
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
        for (contact in contactDao.observeAllContactsOnce()) {
            runCatching {
                chatRepository.getOrCreateChat(contact.id, contact.displayName)
                chatRepository.mergeDuplicateChats(contact.id)
            }
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