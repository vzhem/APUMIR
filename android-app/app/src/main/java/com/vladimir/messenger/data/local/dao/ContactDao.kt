package com.vladimir.messenger.data.local.dao

import androidx.room.*
import com.vladimir.messenger.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun observeAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT id FROM contacts")
    suspend fun allIds(): List<String>

    @Query("SELECT * FROM contacts WHERE id = :contactId")
    suspend fun getContactById(contactId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE fingerprint = :fingerprint")
    suspend fun getContactByFingerprint(fingerprint: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Update
    suspend fun updateContact(contact: ContactEntity)

    @Delete
    suspend fun deleteContact(contact: ContactEntity)

    @Query("UPDATE contacts SET isOnline = :isOnline WHERE id = :contactId")
    suspend fun updateOnlineStatus(contactId: String, isOnline: Boolean)

    /** Холодный старт: все статусы гасим, пока peer_discovered не включит живых. */
    @Query("UPDATE contacts SET isOnline = 0")
    suspend fun setAllOffline()

    @Query("UPDATE contacts SET displayName = :name WHERE id = :contactId")
    suspend fun updateDisplayName(contactId: String, name: String)

    @Query("UPDATE contacts SET username = :username WHERE id = :contactId")
    suspend fun updateUsername(contactId: String, username: String)
}
