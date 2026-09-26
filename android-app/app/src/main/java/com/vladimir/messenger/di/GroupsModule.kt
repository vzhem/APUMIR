package com.vladimir.messenger.di

/** Раунд 180: последний сжатый аватар - чтобы не жечь ЦП на каждый whois. */
private object MyAvatarMemo {
    @Volatile var uri: String? = null
    @Volatile var b64: String? = null
}

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
import com.vladimir.messenger.data.swarm.SwarmBudget
import com.vladimir.messenger.data.swarm.SwarmLane
import com.vladimir.messenger.data.swarm.SwarmPeerDirectory
import com.vladimir.messenger.data.swarm.SwarmSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Module
@InstallIn(SingletonComponent::class)
object GroupsModule {

    private const val IDENTITY_PREFS = "p2p_prefs"
    private const val DISPLAY_NAME_KEY = "display_name"

    /**
     * Один бюджет исходящих групповых пакетов на весь телефон. Пределы
     * берутся из настроек «Раздача» и обстановки (мобильный интернет, низкий
     * заряд) при каждом решении - смена режима действует сразу.
     */
    @Provides
    @Singleton
    fun provideSwarmBudget(
        @ApplicationContext context: Context,
    ): SwarmBudget = SwarmBudget(limits = { SwarmSettings.limits(context.applicationContext) })

    /**
     * Групповой веер поверх существующей отправки 1:1. Rust-ядро не менялось:
     * каждый участник получает обычный APUGRP1-конверт как личное сообщение,
     * а групповой роутер разбирает его до сохранения в чат.
     */
    @Provides
    @Singleton
    fun provideGroupDelivery(
        @ApplicationContext context: Context,
        directory: SwarmPeerDirectory,
        budget: SwarmBudget,
    ): GroupDelivery = PerMemberFanoutDelivery(
        // Всегда в IO. Вызов ядра блокирующий: прямой QUIC ждёт до 5 с на
        // соединение и до 5 с на запись, а веер зовут из viewModelScope, то
        // есть с главного потока, и async внутри веера наследует его. Пост в
        // канал с фото на телефоне владельца давал «APU не отвечает» каждые
        // пять секунд (2026-09-08): тема, потом сообщение - по 10 с каждое.
        send = { groupId, recipientId, envelope ->
            withContext(Dispatchers.IO) {
                RustBridge.sendMessage(UUID.randomUUID().toString(), groupId, recipientId, envelope)
            }
        },
        // Очередь решают ярус и рейтинг: сначала свои, проверенные,
        // стабильные (внутри - надёжные и быстрые первыми), потом остальные.
        order = { ids ->
            runCatching { directory.order(ids) }.getOrDefault(ids)
        },
        // Ширина веера и темп - из действующих пределов (режим + обстановка).
        concurrency = { SwarmSettings.limits(context.applicationContext).maxConcurrentSends },
        gate = { budget.acquire(SwarmLane.CONTENT) },
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
        manifestDao: com.vladimir.messenger.data.local.dao.PostManifestDao,
        signerDao: com.vladimir.messenger.data.local.dao.PostSignerDao,
        directory: SwarmPeerDirectory,
        counters: com.vladimir.messenger.data.channel.PostCounterRepository,
        shortener: com.vladimir.messenger.data.link.LinkShortener,
        groupFiles: javax.inject.Provider<com.vladimir.messenger.data.group.GroupFileSwarm>,
        // Рой APK (обновление роем, docs/UPDATE_SEEDING.md): те же кольца,
        // что у файлов группы, — через Provider.
        apkSeeder: javax.inject.Provider<com.vladimir.messenger.data.update.ApkSeeder>,
        // Раунд 137: очередь «удалить у всех» - команда хранится и досылается,
        // пока не дойдёт до всех (через тех, кто в сети).
        deletionOutbox: com.vladimir.messenger.data.repository.DeletionOutbox,
        @ApplicationContext context: Context,
    ): GroupRepository = GroupRepository(
        groupDao = groupDao,
        messageDao = messageDao,
        delivery = delivery,
        directoryDao = directoryDao,
        // Рой постов (этап 1): манифесты, ключи подписантов, подпись ключом
        // личности через Java-библиотеку eddsa (см. PostSigner), соседи по
        // ярусам из того же справочника, что и очередь веера.
        manifestDao = manifestDao,
        signerDao = signerDao,
        signManifest = { manifest ->
            com.vladimir.messenger.data.swarm.PostSigner.sign(context.applicationContext, manifest)
        },
        myPostKey = { com.vladimir.messenger.data.swarm.PostSigner.publicKey(context.applicationContext) },
        orderPeers = { ids -> runCatching { directory.order(ids) }.getOrDefault(ids) },
        // Счётчики через владельца (этап 3): просьбы о сводке и сводки.
        onCountersRequest = { senderId, packet -> counters.serve(senderId, packet) },
        onCounters = { senderId, packet -> counters.applyCounters(senderId, packet) },
        // Короткие ссылки для пересылки: код выдаёт и разворачивает наш
        // сервис (worker, /short); коды кэшируются в LinkShortener.
        shortenLink = { target -> shortener.codeFor(target) },
        expandShortLink = { code -> shortener.expand(code) },
        // Место под пересылку: своё объявляем контактам (`cap`), чужое
        // объявление - в рейтинг узлов (не больше 10 баллов, взвешено
        // наблюдаемой доступностью).
        myOfferedStorageBytes = {
            com.vladimir.messenger.data.swarm.StorageSettings.quota(context.applicationContext)
        },
        onPeerCapabilities = { nodeId, offeredBytes ->
            com.vladimir.messenger.data.peer.PeerRatingStore
                .recordOfferedStorage(context.applicationContext, nodeId, offeredBytes)
        },
        // Наследование владения: «владелец удалился» видно по долгому молчанию
        // (GroupOwnership). Берём наблюдения этого телефона из рейтинга узлов.
        peerLastSeenMs = { nodeId ->
            com.vladimir.messenger.data.peer.PeerRatingStore
                .statsFor(context.applicationContext, nodeId)?.lastSeenMs?.takeIf { it > 0L }
        },
        // Раунд 137: очередь «удалить у всех» - команда хранится и досылается.
        deletionOutbox = deletionOutbox,
        // Файлы группы роем (этап 9). Через Provider: рой сам шлёт пакеты
        // через GroupDelivery и качает через FileTransferRouter, а репозиторий
        // лишь передаёт ему просьбы - кольца зависимостей так нет.
        onFileWant = { senderId, packet -> groupFiles.get().onFileWant(senderId, packet) },
        onFileHave = { senderId, packet -> groupFiles.get().onFileHave(senderId, packet) },
        onFileNone = { senderId, packet -> groupFiles.get().onFileNone(senderId, packet) },
        onGroupGone = { groupId -> groupFiles.get().onGroupGone(groupId) },
        onFileCard = { groupId, messageId, authorId, sentAtMs, info ->
            groupFiles.get().onCardSeen(groupId, messageId, authorId, sentAtMs, info)
        },
        // Рой APK: пакеты обновления — в сидер (docs/UPDATE_SEEDING.md).
        onUpdatePack = { senderId, packet -> apkSeeder.get().onUpdatePack(senderId, packet) },
        onUpdateWant = { senderId, packet -> apkSeeder.get().onUpdateWant(senderId, packet) },
        onUpdateNone = { senderId, packet -> apkSeeder.get().onUpdateNone(senderId, packet) },
        onUpdateAsk = { senderId, packet -> apkSeeder.get().onUpdateAsk(senderId, packet) },
        // Раунд 133: дифф-патч обновления — тот же сидер.
        onUpdatePatchPack = { senderId, packet -> apkSeeder.get().onUpdatePatchPack(senderId, packet) },
        onUpdatePatchWant = { senderId, packet -> apkSeeder.get().onUpdatePatchWant(senderId, packet) },
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
            // Раунд 180 (аудит-2): сжатие аватара - только когда он сменился
            // (uri другой). Раньше каждый ответ «представься» и каждая
            // рассылка сжимали картинку заново.
            val uri = context.applicationContext
                .getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
                .getString("my_avatar_uri", null)
                ?.takeIf { it.isNotBlank() }
            if (uri == null) {
                MyAvatarMemo.uri = null
                MyAvatarMemo.b64 = null
                null
            } else if (uri == MyAvatarMemo.uri && MyAvatarMemo.b64 != null) {
                MyAvatarMemo.b64
            } else {
                val b64 = com.vladimir.messenger.util.AvatarCompress.compressUri(
                    context.applicationContext, uri,
                )
                if (b64 != null) {
                    MyAvatarMemo.uri = uri
                    MyAvatarMemo.b64 = b64
                }
                b64
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
