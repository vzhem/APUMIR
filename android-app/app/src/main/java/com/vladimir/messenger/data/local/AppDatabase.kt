package com.vladimir.messenger.data.local

import com.vladimir.messenger.domain.model.MessageChannel

import androidx.room.Database
import com.vladimir.messenger.data.local.entity.MtProtoProxyEntity
import androidx.room.RoomDatabase
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.MtProtoProxyDao
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.ContactEntity
import com.vladimir.messenger.data.local.entity.MessageEntity

@Database(
    entities = [MtProtoProxyEntity::class, 
        ChatEntity::class,
        MessageEntity::class,
        ContactEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun mtProtoProxyDao(): MtProtoProxyDao
    abstract fun contactDao(): ContactDao
}
