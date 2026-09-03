package com.vladimir.messenger.di

import android.content.Context
import androidx.room.Room
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.DirectoryDao
import com.vladimir.messenger.data.local.dao.AvatarDao
import com.vladimir.messenger.data.local.dao.NicknameDao
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.dao.FileExchangePeerDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.MtProtoProxyDao
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.repository.ContactRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "messenger_database")
            .addMigrations(
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides @Singleton
    fun provideChatDao(db: AppDatabase): ChatDao = db.chatDao()

    @Provides @Singleton
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()

    @Provides @Singleton
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides @Singleton
    fun provideMessageReactionDao(
        db: AppDatabase,
    ): com.vladimir.messenger.data.local.dao.MessageReactionDao = db.messageReactionDao()

    @Provides @Singleton
    fun provideMtProtoProxyDao(db: AppDatabase): MtProtoProxyDao = db.mtProtoProxyDao()

    @Provides @Singleton
    fun provideFileTransferDao(db: AppDatabase): FileTransferDao = db.fileTransferDao()

    @Provides @Singleton
    fun provideSavedItemDao(db: AppDatabase): com.vladimir.messenger.data.local.dao.SavedItemDao =
        db.savedItemDao()

    @Provides @Singleton
    fun provideFileExchangePeerDao(db: AppDatabase): FileExchangePeerDao = db.fileExchangePeerDao()

    @Provides @Singleton
    fun provideGroupDao(db: AppDatabase): GroupDao = db.groupDao()

    @Provides @Singleton
    fun provideDirectoryDao(db: AppDatabase): DirectoryDao = db.directoryDao()

    @Provides @Singleton
    fun provideNicknameDao(db: AppDatabase): NicknameDao = db.nicknameDao()

    @Provides @Singleton
    fun provideAvatarDao(db: AppDatabase): AvatarDao = db.avatarDao()

    @Provides @Singleton
    fun provideChatRepository(
        chatDao: ChatDao,
        messageDao: MessageDao,
        referralAttribution: com.vladimir.messenger.data.referral.ReferralAttributionSender,
    ): ChatRepository = ChatRepository(chatDao, messageDao, referralAttribution)

    @Provides @Singleton
    fun provideContactRepository(contactDao: ContactDao, chatRepository: ChatRepository): ContactRepository =
        ContactRepository(contactDao, chatRepository)
}