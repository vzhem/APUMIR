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
) {
    fun observeContacts(): Flow<List<Contact>> =
        contactDao.observeAllContacts().map { it.map { e -> e.toDomain() } }

    suspend fun addContact(displayName: String, fingerprint: String): Result<Contact> {
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

    suspend fun updateDisplayName(contactId: String, name: String) {
        contactDao.updateDisplayName(contactId, name)
    }

    suspend fun deleteContact(contactId: String) {
        val entity = contactDao.getContactById(contactId) ?: return
        contactDao.deleteContact(entity)
    }

    private fun ContactEntity.toDomain() = Contact(
        id = id,
        displayName = displayName,
        fingerprint = fingerprint,
        isOnline = isOnline,
        lastSeen = lastSeen,
    )
}