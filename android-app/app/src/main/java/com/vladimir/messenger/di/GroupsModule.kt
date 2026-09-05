package com.vladimir.messenger.di

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.group.GroupDelivery
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.GroupRouter
import com.vladimir.messenger.data.group.PerMemberFanoutDelivery
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.DirectoryDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.AvatarDao
import com.vladimir.messenger.data.local.dao.NicknameDao
import com.vladimir.messenger.data.referral.ReferralRankStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GroupsModule {

    private const val IDENTITY_PREFS = "p2p_prefs"
    private const val DISPLAY_NAME_KEY = "display_name"

    /**
     * Групповой веер поверх существующей отправки 1:1. Rust-ядро не менялось:
     * каждый участник получает обычный APUGRP1-конверт как личное сообщение,
     * а групповой роутер разбирает его до сохранения в чат.
     */
    @Provides
    @Singleton
    fun provideGroupDelivery(
        @ApplicationContext context: Context,
    ): GroupDelivery = PerMemberFanoutDelivery(
        send = { groupId, recipientId, envelope ->
            RustBridge.sendMessage(UUID.randomUUID().toString(), groupId, recipientId, envelope)
        },
        // Рейтинг решает очередь: надёжные и быстрые узлы получают конверт
        // первыми, остальные - следом.
        order = { ids ->
            runCatching {
                com.vladimir.messenger.data.peer.PeerRatingStore
                    .preferredOrder(context.applicationContext, ids)
            }.getOrDefault(ids)
        },
    )

    @Provides
    @Singleton
    fun provideGroupRepository(
        groupDao: GroupDao,
        messageDao: MessageDao,
        delivery: GroupDelivery,
        directoryDao: DirectoryDao,
        contactDao: ContactDao,
        nicknameDao: NicknameDao,
        avatarDao: AvatarDao,
        contactRepository: com.vladimir.messenger.data.repository.ContactRepository,
        chatRepository: com.vladimir.messenger.data.repository.ChatRepository,
        @ApplicationContext context: Context,
    ): GroupRepository = GroupRepository(
        groupDao = groupDao,
        messageDao = messageDao,
        delivery = delivery,
        directoryDao = directoryDao,
        contactIds = { contactDao.allIds() },
        nicknameDao = nicknameDao,
        myUsername = {
            com.vladimir.messenger.ui.theme.UsernameHolder.normalize(
                context.applicationContext
                    .getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
                    .getString("my_username", null)
            )
        },
        myRegisteredAt = {
            context.applicationContext
                .getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
                .getLong("my_nick_registered_at", 0L)
        },
        onUsernameConflict = {
            // Снимаем проигравшее имя и показываем предложение задать новое.
            com.vladimir.messenger.ui.theme.UsernameHolder.set(context.applicationContext, null)
            com.vladimir.messenger.ui.theme.UsernameHolder.raiseConflict(context.applicationContext)
        },
        avatarDao = avatarDao,
        myAvatarB64 = {
            val uri = context.applicationContext
                .getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
                .getString("my_avatar_uri", null)
            uri?.takeIf { it.isNotBlank() }?.let {
                com.vladimir.messenger.util.AvatarCompress.compressUri(context.applicationContext, it)
            }
        },
        // Узнали настоящее @имя узла: подменяем им заглушку в контакте и
        // схлопываем двойников от прежних установок той же трубки.
        onNicknameLearned = { ownerId, nickname ->
            com.vladimir.messenger.data.repository.NicknameIdentity.apply(
                ownerId = ownerId,
                nickname = nickname,
                contactRepository = contactRepository,
                chatRepository = chatRepository,
            )
        },
        myNodeId = { RustBridge.nodeId() },
        myDisplayName = {
            context.applicationContext
                .getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
                .getString(DISPLAY_NAME_KEY, "")
                .orEmpty()
        },
        // Создание групп доступно с ранга «Проводник» (10 квалифицированных
        // приглашённых) — правило MASTER_PLAN и FileTransferRankPolicy.
        canCreateGroups = {
            val qualified = ReferralRankStore.qualifiedDirectCount(context.applicationContext)
            FileTransferRankPolicy.entitlement(qualified).canCreateGroup
        },
    )

    @Provides
    @Singleton
    fun provideGroupRouter(repository: GroupRepository): GroupRouter = GroupRouter(repository)
}
