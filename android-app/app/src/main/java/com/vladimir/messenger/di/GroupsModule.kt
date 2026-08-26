package com.vladimir.messenger.di

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.group.GroupDelivery
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.GroupRouter
import com.vladimir.messenger.data.group.PerMemberFanoutDelivery
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
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
    fun provideGroupDelivery(): GroupDelivery = PerMemberFanoutDelivery(
        send = { groupId, recipientId, envelope ->
            RustBridge.sendMessage(UUID.randomUUID().toString(), groupId, recipientId, envelope)
        },
    )

    @Provides
    @Singleton
    fun provideGroupRepository(
        groupDao: GroupDao,
        messageDao: MessageDao,
        delivery: GroupDelivery,
        @ApplicationContext context: Context,
    ): GroupRepository = GroupRepository(
        groupDao = groupDao,
        messageDao = messageDao,
        delivery = delivery,
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
