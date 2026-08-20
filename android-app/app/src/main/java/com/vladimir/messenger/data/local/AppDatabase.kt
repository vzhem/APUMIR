package com.vladimir.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.dao.FileExchangePeerDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.MtProtoProxyDao
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.ContactEntity
import com.vladimir.messenger.data.local.entity.FileTransferChunkEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.local.entity.FileExchangePeerEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.local.entity.MtProtoProxyEntity

@Database(
    entities = [
        MtProtoProxyEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        ContactEntity::class,
        FileTransferEntity::class,
        FileTransferChunkEntity::class,
        FileExchangePeerEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun mtProtoProxyDao(): MtProtoProxyDao
    abstract fun contactDao(): ContactDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun fileExchangePeerDao(): FileExchangePeerDao

    companion object {
        /** Additive migration: existing chats/messages/contacts are never rewritten or deleted. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `file_transfers` (
                        `transferId` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `chatId` TEXT NOT NULL,
                        `peerNodeId` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `mediaType` TEXT NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `chunkSize` INTEGER NOT NULL,
                        `chunkCount` INTEGER NOT NULL,
                        `fileSha256` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `completedChunks` INTEGER NOT NULL,
                        `transferredBytes` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `expiresAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        `errorCode` TEXT,
                        PRIMARY KEY(`transferId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `file_transfer_chunks` (
                        `transferId` TEXT NOT NULL,
                        `chunkIndex` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `ciphertextBytes` INTEGER NOT NULL,
                        `chunkSha256` TEXT,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`transferId`, `chunkIndex`),
                        FOREIGN KEY(`transferId`) REFERENCES `file_transfers`(`transferId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_transfers_chatId` ON `file_transfers` (`chatId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_transfers_state` ON `file_transfers` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_transfers_expiresAtMs` ON `file_transfers` (`expiresAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_transfer_chunks_transferId` ON `file_transfer_chunks` (`transferId`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `file_exchange_peers` (
                        `nodeId` TEXT NOT NULL,
                        `bindingBase64` TEXT NOT NULL,
                        `bindingSha256` TEXT NOT NULL,
                        `x25519PublicHex` TEXT NOT NULL,
                        `trustState` TEXT NOT NULL,
                        `firstSeenAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`nodeId`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
