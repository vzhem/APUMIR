package com.vladimir.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.DirectoryDao
import com.vladimir.messenger.data.local.dao.AvatarDao
import com.vladimir.messenger.data.local.dao.NicknameDao
import com.vladimir.messenger.data.local.dao.PeerRankDao
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.dao.FileExchangePeerDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.InboxPinDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.MessageReactionDao
import com.vladimir.messenger.data.local.dao.MtProtoProxyDao
import com.vladimir.messenger.data.local.dao.PostViewDao
import com.vladimir.messenger.data.local.dao.ProfileHeartDao
import com.vladimir.messenger.data.local.dao.SavedItemDao
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.ContactEntity
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import com.vladimir.messenger.data.local.entity.NicknameEntity
import com.vladimir.messenger.data.local.entity.FileTransferChunkEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.local.entity.FileExchangePeerEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.local.entity.MessageReactionEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.GroupInviteEntity
import com.vladimir.messenger.data.local.entity.GroupJoinRequestEntity
import com.vladimir.messenger.data.local.entity.GroupMemberEntity
import com.vladimir.messenger.data.local.entity.GroupMessageStatEntity
import com.vladimir.messenger.data.local.entity.GroupTopicEntity
import com.vladimir.messenger.data.local.entity.AvatarEntity
import com.vladimir.messenger.data.local.entity.MtProtoProxyEntity
import com.vladimir.messenger.data.local.entity.PeerRankEntity
import com.vladimir.messenger.data.local.entity.PostViewEntity
import com.vladimir.messenger.data.local.entity.ProfileHeartEntity
import com.vladimir.messenger.data.local.entity.SavedItemEntity
import com.vladimir.messenger.data.local.entity.PostManifestEntity
import com.vladimir.messenger.data.local.entity.PostSignerEntity
import com.vladimir.messenger.data.local.entity.GroupPollEntity
import com.vladimir.messenger.data.local.entity.GroupPollVoteEntity
import com.vladimir.messenger.data.local.dao.PostManifestDao
import com.vladimir.messenger.data.local.dao.PostSignerDao
import com.vladimir.messenger.data.local.dao.GroupPollDao

/**
 * Версия схемы. Вынесена в константу, потому что её сверяет резервная копия
 * (`data/backup`): копию с более новой базой восстанавливать нельзя, со старой -
 * миграции ниже доведут сами.
 */
const val APP_DATABASE_VERSION = 28

@Database(
    entities = [
        MtProtoProxyEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        MessageReactionEntity::class,
        ContactEntity::class,
        PeerRankEntity::class,
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
        AvatarEntity::class,
        SavedItemEntity::class,
        ProfileHeartEntity::class,
        PostViewEntity::class,
        PostManifestEntity::class,
        PostSignerEntity::class,
        GroupPollEntity::class,
        GroupPollVoteEntity::class,
    ],
    version = APP_DATABASE_VERSION,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun messageReactionDao(): MessageReactionDao
    abstract fun mtProtoProxyDao(): MtProtoProxyDao
    abstract fun contactDao(): ContactDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun fileExchangePeerDao(): FileExchangePeerDao
    abstract fun groupDao(): GroupDao
    abstract fun peerRankDao(): PeerRankDao
    abstract fun inboxPinDao(): InboxPinDao
    abstract fun directoryDao(): DirectoryDao
    abstract fun nicknameDao(): NicknameDao
    abstract fun avatarDao(): AvatarDao
    abstract fun savedItemDao(): SavedItemDao
    abstract fun profileHeartDao(): ProfileHeartDao
    abstract fun postViewDao(): PostViewDao
    abstract fun postManifestDao(): PostManifestDao
    abstract fun postSignerDao(): PostSignerDao
    abstract fun groupPollDao(): GroupPollDao

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
         * v10: у контактов появляется оригинальное имя через собаку (@nickname),
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

        /**
         * v12: реестр присланных аватаров. Операция добавочная, существующие
         * данные не трогает.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `avatars` (" +
                        "`ownerId` TEXT NOT NULL, " +
                        "`dataB64` TEXT NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerId`))"
                )
            }
        }

        /**
         * v13: «Избранное» - личное хранилище абонента. Операция добавочная,
         * существующие чаты, сообщения и файлы не трогает.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_items` (" +
                        "`id` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`transferId` TEXT, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`mediaType` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`sourceTitle` TEXT NOT NULL, " +
                        "`savedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_saved_items_savedAtMs` " +
                        "ON `saved_items` (`savedAtMs`)"
                )
            }
        }

        /** Реакции на сообщения: новая таблица, ничего существующего не трогаем. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_reactions` (" +
                        "`messageId` TEXT NOT NULL, " +
                        "`nodeId` TEXT NOT NULL, " +
                        "`chatId` TEXT NOT NULL, " +
                        "`emoji` TEXT NOT NULL, " +
                        "`atMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`messageId`, `nodeId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_reactions_messageId` " +
                        "ON `message_reactions` (`messageId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_reactions_chatId` " +
                        "ON `message_reactions` (`chatId`)"
                )
            }
        }

        /** Манифесты постов канала (рой, этап 1): подписанный список текста и кусков фото. */
        /** Хранение чужих файлов для получателей не в сети (этап 7 роя): чей файл. */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `file_transfers` ADD COLUMN `originNodeId` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `file_transfers` ADD COLUMN `custodianNodeId` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** Раунд 187: закрепы в «Избранном» - честная миграция, данные целы. */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `saved_items` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `saved_items` ADD COLUMN `pinnedAtMs` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** Раунд 204: закрепления бесед главного списка (лички, группы, каналы). */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chats` ADD COLUMN `pinnedAtMs` INTEGER")
                db.execSQL("ALTER TABLE `groups` ADD COLUMN `pinnedAtMs` INTEGER")
            }
        }

        /**
         * р249: архив и «без звука» у чатов, групп и каналов.
         *
         * Аддитивная миграция: существующие строки не переписываются, оба
         * флага по умолчанию выключены (архив пуст, звук включён). Флаги
         * живут рядом с закрепом главного списка и так же уезжают на второе
         * устройство личности кадром зеркала.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `chats` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `chats` ADD COLUMN `mutedUntilMs` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `groups` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `groups` ADD COLUMN `mutedUntilMs` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * р250: ответы на сообщения, медленный режим групп и опросы.
         *
         * Аддитивная миграция: ни одна существующая строка не переписывается и
         * не удаляется. Прежние сообщения остаются без цитат (`replyToId`
         * пуст), группы - без медленного режима, опросов нет ни у кого.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ответ: кого цитируем и что цитируем. Цитата приезжает
                // отдельным конвертом `mrep`, поэтому хранится рядом с текстом,
                // а не вычисляется по replyToId при показе.
                db.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `replyAuthor` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `replyText` TEXT NOT NULL DEFAULT ''"
                )
                // Медленный режим: 0 - выключен.
                db.execSQL(
                    "ALTER TABLE `groups` ADD COLUMN `slowModeSeconds` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_polls` (" +
                        "`pollId` TEXT NOT NULL, " +
                        "`groupId` TEXT NOT NULL, " +
                        "`topicId` TEXT NOT NULL, " +
                        "`messageId` TEXT NOT NULL, " +
                        "`question` TEXT NOT NULL, " +
                        "`optionsCsv` TEXT NOT NULL, " +
                        "`anonymous` INTEGER NOT NULL DEFAULT 0, " +
                        "`multiChoice` INTEGER NOT NULL DEFAULT 0, " +
                        "`creatorId` TEXT NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, " +
                        "`closed` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`pollId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_polls_groupId` " +
                        "ON `group_polls` (`groupId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_polls_messageId` " +
                        "ON `group_polls` (`messageId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_poll_votes` (" +
                        "`pollId` TEXT NOT NULL, " +
                        "`voterId` TEXT NOT NULL, " +
                        "`voterName` TEXT NOT NULL, " +
                        "`choicesCsv` TEXT NOT NULL, " +
                        "`atMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`pollId`, `voterId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_poll_votes_pollId` " +
                        "ON `group_poll_votes` (`pollId`)"
                )
            }
        }

        /**
         * Last-presence timestamps for the Contacts screen.
         *
         * The existing `lastSeen` column was a never-written text placeholder.
         * Keep it intact for old installations and add a typed timestamp beside
         * it; no contact name, chat, or history is touched by this migration.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `lastSeenAtMs` INTEGER")
            }
        }

        /**
         * Ранги собеседников: одна таблица на все узлы (решение владельца
         * 2026-10-07 — «VIP должно быть видно везде, и в группах, и в каналах»).
         *
         * Раньше ранг лежал колонкой в `contacts`, и у человека не из адресной
         * книги знака не было. Здесь уже принятые ранги переносятся в новую
         * таблицу: данные, пришедшие до обновления, не теряются. Колонки в
         * `contacts` остаются в схеме (обратная совместимость), но больше не
         * пишутся — источник истины один.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `peer_ranks` (" +
                        "`nodeId` TEXT NOT NULL, " +
                        "`qualified` INTEGER NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`nodeId`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `peer_ranks` (`nodeId`, `qualified`, `updatedAtMs`) " +
                        "SELECT lower(`id`), `peerRankQualified`, `peerRankUpdatedAtMs` " +
                        "FROM `contacts` WHERE `peerRankQualified` >= 0 " +
                        "AND `peerRankUpdatedAtMs` > 0"
                )
            }
        }

        /**
         * Ранг собеседника для знака VIP у имени (решение владельца 2026-10-06/07).
         *
         * Аддитивная миграция: ни одна строка не переписывается. `-1` значит
         * «собеседник ещё не сообщал свой ранг» — у таких контактов знак не
         * рисуется, пока не придёт первый конверт APURANK1.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `contacts` ADD COLUMN `peerRankQualified` INTEGER NOT NULL DEFAULT -1"
                )
                db.execSQL(
                    "ALTER TABLE `contacts` ADD COLUMN `peerRankUpdatedAtMs` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** Indexes keep per-chat history and the notification window off full-table scans. */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_chatId_timestamp` " +
                        "ON `messages` (`chatId`, `timestamp`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_isFromMe_timestamp` " +
                        "ON `messages` (`isFromMe`, `timestamp`)"
                )
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_manifests` (" +
                        "`messageId` TEXT NOT NULL, " +
                        "`groupId` TEXT NOT NULL, " +
                        "`topicId` TEXT NOT NULL, " +
                        "`authorId` TEXT NOT NULL, " +
                        "`sentAtMs` INTEGER NOT NULL, " +
                        "`textSha` TEXT NOT NULL, " +
                        "`parts` TEXT NOT NULL, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`signerKey` TEXT NOT NULL, " +
                        "`signature` TEXT NOT NULL, " +
                        "`receivedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`messageId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_post_manifests_groupId` " +
                        "ON `post_manifests` (`groupId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_post_manifests_topicId` " +
                        "ON `post_manifests` (`topicId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_signers` (" +
                        "`nodeId` TEXT NOT NULL, " +
                        "`attestedBy` TEXT NOT NULL, " +
                        "`publicKey` TEXT NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`nodeId`, `attestedBy`))"
                )
            }
        }

        /** Фотографии сохранённого поста канала - одной колонкой в «Избранном». */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `saved_items` ADD COLUMN `photos` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** Просмотры постов канала: по строке на читателя. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_views` (" +
                        "`topicId` TEXT NOT NULL, " +
                        "`viewerId` TEXT NOT NULL, " +
                        "`atMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`topicId`, `viewerId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_post_views_topicId` " +
                        "ON `post_views` (`topicId`)"
                )
            }
        }

        /** Сердечки профилям: рейтинг популярности. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profile_hearts` (" +
                        "`ownerId` TEXT NOT NULL, " +
                        "`voterId` TEXT NOT NULL, " +
                        "`atMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerId`, `voterId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_profile_hearts_ownerId` " +
                        "ON `profile_hearts` (`ownerId`)"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf(
                    "originKind",
                    "originId",
                    "originTopicId",
                    "originName",
                    "originContactId",
                )) {
                    db.execSQL(
                        "ALTER TABLE `saved_items` ADD COLUMN `" + column +
                            "` TEXT NOT NULL DEFAULT ''"
                    )
                }
            }
        }

    }
}
