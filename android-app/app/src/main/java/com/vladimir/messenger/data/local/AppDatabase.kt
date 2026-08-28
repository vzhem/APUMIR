package com.vladimir.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.DirectoryDao
import com.vladimir.messenger.data.local.dao.NicknameDao
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.dao.FileExchangePeerDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.MtProtoProxyDao
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.ContactEntity
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import com.vladimir.messenger.data.local.entity.NicknameEntity
import com.vladimir.messenger.data.local.entity.FileTransferChunkEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.local.entity.FileExchangePeerEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.GroupInviteEntity
import com.vladimir.messenger.data.local.entity.GroupJoinRequestEntity
import com.vladimir.messenger.data.local.entity.GroupMemberEntity
import com.vladimir.messenger.data.local.entity.GroupMessageStatEntity
import com.vladimir.messenger.data.local.entity.GroupTopicEntity
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
        GroupEntity::class,
        GroupMemberEntity::class,
        GroupTopicEntity::class,
        GroupJoinRequestEntity::class,
        GroupInviteEntity::class,
        GroupMessageStatEntity::class,
        DirectoryEntity::class,
        NicknameEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun mtProtoProxyDao(): MtProtoProxyDao
    abstract fun contactDao(): ContactDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun fileExchangePeerDao(): FileExchangePeerDao
    abstract fun groupDao(): GroupDao
    abstract fun directoryDao(): DirectoryDao
    abstract fun nicknameDao(): NicknameDao

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

        /**
         * Группы, темы, заявки, приглашения и статистика (v7 -> v8).
         *
         * Миграция строго аддитивная: существующие chats/messages/contacts не
         * переписываются и не удаляются. В таблицу messages добавляются четыре
         * столбца для тем и закрепов; у isPinned задан DEFAULT 0, чтобы схема
         * совпала с @ColumnInfo(defaultValue = "0") при валидации Room.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `topicId` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `pinnedAtMs` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `pinnedBy` TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `groups` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `about` TEXT NOT NULL,
                        `ownerId` TEXT NOT NULL,
                        `ownerName` TEXT NOT NULL,
                        `isPublic` INTEGER NOT NULL,
                        `topicsEnabled` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `memberCount` INTEGER NOT NULL,
                        `inviteSlug` TEXT NOT NULL,
                        `memberPermissions` INTEGER NOT NULL,
                        `isLeft` INTEGER NOT NULL,
                        `lastMessagePreview` TEXT,
                        `lastMessageAtMs` INTEGER,
                        `unreadCount` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_members` (
                        `groupId` TEXT NOT NULL,
                        `nodeId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `joinedAtMs` INTEGER NOT NULL,
                        `permissions` INTEGER NOT NULL,
                        `customTitle` TEXT NOT NULL,
                        `isBanned` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`, `nodeId`),
                        FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_members_groupId` ON `group_members` (`groupId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_members_nodeId` ON `group_members` (`nodeId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_topics` (
                        `id` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `ownerId` TEXT NOT NULL,
                        `ownerName` TEXT NOT NULL,
                        `iconEmoji` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `messageCount` INTEGER NOT NULL,
                        `unreadCount` INTEGER NOT NULL,
                        `lastMessagePreview` TEXT,
                        `lastMessageAtMs` INTEGER,
                        `isClosed` INTEGER NOT NULL,
                        `isGeneral` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_topics_groupId` ON `group_topics` (`groupId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_join_requests` (
                        `groupId` TEXT NOT NULL,
                        `nodeId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `requestedAtMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `decidedAtMs` INTEGER,
                        `decidedBy` TEXT NOT NULL,
                        PRIMARY KEY(`groupId`, `nodeId`),
                        FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_join_requests_groupId` ON `group_join_requests` (`groupId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_join_requests_status` ON `group_join_requests` (`status`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_invites` (
                        `slug` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        `createdBy` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `expiresAtMs` INTEGER,
                        `maxUses` INTEGER NOT NULL,
                        `useCount` INTEGER NOT NULL,
                        `revoked` INTEGER NOT NULL,
                        `requestApproval` INTEGER NOT NULL,
                        PRIMARY KEY(`slug`),
                        FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_invites_groupId` ON `group_invites` (`groupId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_invites_slug` ON `group_invites` (`slug`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_message_stats` (
                        `groupId` TEXT NOT NULL,
                        `topicId` TEXT NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `messageCount` INTEGER NOT NULL,
                        `senderCount` INTEGER NOT NULL,
                        `sendersCsv` TEXT NOT NULL,
                        PRIMARY KEY(`groupId`, `topicId`, `dayKey`),
                        FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_message_stats_groupId` ON `group_message_stats` (`groupId`)")
            }
        }

        /**
         * 8 -> 9: каналы.
         *
         * Канал - это группа с флагом: те же участники, доставка, темы и
         * админ-кабинет, но посты пишут администраторы, а обсуждение живёт в
         * комментариях под постом. Поэтому хватает одного столбца, таблицы не
         * пересоздаются и данные не теряются.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `groups` ADD COLUMN `isChannel` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v10: у контактов появляется оригинальное имя через собаку (@evzhem),
         * а для поиска по чужим группам и каналам - таблица сетевого каталога,
         * куда роевая рассылка складывает публичные группы и каналы.
         * Обе операции добавочные: существующие строки не переписываются.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `contacts` ADD COLUMN `username` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `directory` (" +
                        "`groupId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`about` TEXT NOT NULL, " +
                        "`ownerId` TEXT NOT NULL, " +
                        "`slug` TEXT NOT NULL, " +
                        "`isChannel` INTEGER NOT NULL, " +
                        "`needsApproval` INTEGER NOT NULL, " +
                        "`hops` INTEGER NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupId`))"
                )
            }
        }

        /**
         * v11: реестр @имён для роевой проверки уникальности. Операция
         * добавочная, существующие данные не трогает.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `nicknames` (" +
                        "`ownerId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`registeredAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerId`))"
                )
            }
        }

    }
}
