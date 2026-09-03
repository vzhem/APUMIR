package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.GroupInviteEntity
import com.vladimir.messenger.data.local.entity.GroupJoinRequestEntity
import com.vladimir.messenger.data.local.entity.GroupMemberEntity
import com.vladimir.messenger.data.local.entity.GroupMessageStatEntity
import com.vladimir.messenger.data.local.entity.GroupTopicEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    // ── Группы ────────────────────────────────────────────────────────────────
    // Каналы лежат в той же таблице, поэтому в выборках групп они отсекаются:
    // иначе канал показывался бы и в списке групп, и в списке каналов.
    @Query("SELECT * FROM groups WHERE isLeft = 0 AND isChannel = 0 ORDER BY lastMessageAtMs DESC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE isLeft = 0 AND isChannel = 0 ORDER BY lastMessageAtMs DESC")
    suspend fun getGroups(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE isLeft = 0 AND isChannel = 1 ORDER BY lastMessageAtMs DESC")
    fun observeChannels(): Flow<List<GroupEntity>>

    // ── Окна для главного экрана ──────────────────────────────────────────
    // Главный экран берёт верхушку списка и досыпает по мере прокрутки,
    // поэтому в память не попадает больше, чем видно.

    @Query(
        "SELECT * FROM groups WHERE isLeft = 0 AND isChannel = 0 " +
            "ORDER BY lastMessageAtMs DESC LIMIT :limit"
    )
    fun observeGroupsWindow(limit: Int): Flow<List<GroupEntity>>

    @Query(
        "SELECT * FROM groups WHERE isLeft = 0 AND isChannel = 1 " +
            "ORDER BY lastMessageAtMs DESC LIMIT :limit"
    )
    fun observeChannelsWindow(limit: Int): Flow<List<GroupEntity>>

    /** Поиск по всей таблице, а не по загруженному окну. */
    @Query(
        "SELECT * FROM groups WHERE isLeft = 0 AND (" +
            "title LIKE '%' || :query || '%' OR lastMessagePreview LIKE '%' || :query || '%') " +
            "ORDER BY lastMessageAtMs DESC LIMIT :limit"
    )
    fun searchGroups(query: String, limit: Int): Flow<List<GroupEntity>>

    /** Мои группы и каналы со ссылкой - их каталог рассылает контактам. */
    @Query("SELECT * FROM groups WHERE ownerId = :ownerId AND isLeft = 0 AND inviteSlug != ''")
    suspend fun getOwnPublishable(ownerId: String): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :groupId")
    suspend fun getGroupById(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE id = :groupId")
    fun observeGroup(groupId: String): Flow<GroupEntity?>

    /**
     * ВАЖНО: ABORT, а не REPLACE.
     *
     * У group_members, group_topics, group_invites, group_join_requests и
     * group_message_stats внешний ключ на groups.id с ON DELETE CASCADE, а
     * INSERT OR REPLACE под капотом УДАЛЯЕТ старую строку группы. Вместе с ней
     * каскадом улетают все участники и все темы - группа остаётся, но пустая,
     * а владелец видит «вы не участник группы». Поэтому здесь ABORT: случайная
     * перезапись упадёт громко, а не тихо сотрёт данные.
     *
     * Обновлять поля существующей группы нужно точечными UPDATE ниже.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: GroupEntity)

    /** Маска прав обычных участников. Только UPDATE: см. предупреждение у insertGroup. */
    @Query("UPDATE groups SET memberPermissions = :mask WHERE id = :groupId")
    suspend fun updateGroupMemberPermissions(groupId: String, mask: Long)

    /**
     * Обновить карточку группы по данным владельца - используется, когда
     * человек вошёл по ссылке и получил пакет GroupInfo. Только UPDATE:
     * перезапись строки стёрла бы участников и темы.
     */
    @Query(
        "UPDATE groups SET title = :title, about = :about, inviteSlug = :inviteSlug, " +
            "isPublic = :isPublic, topicsEnabled = :topicsEnabled WHERE id = :groupId"
    )
    suspend fun updateGroupFromOwner(
        groupId: String,
        title: String,
        about: String,
        inviteSlug: String,
        isPublic: Boolean,
        topicsEnabled: Boolean,
    )

    @Query("UPDATE groups SET title = :title, about = :about WHERE id = :groupId")
    suspend fun updateGroupProfile(groupId: String, title: String, about: String)

    @Query("UPDATE groups SET isPublic = :isPublic WHERE id = :groupId")
    suspend fun updateGroupVisibility(groupId: String, isPublic: Boolean)

    @Query("UPDATE groups SET topicsEnabled = :enabled WHERE id = :groupId")
    suspend fun updateTopicsEnabled(groupId: String, enabled: Boolean)

    @Query("UPDATE groups SET isLeft = 1 WHERE id = :groupId")
    suspend fun markLeft(groupId: String)

    /** Возврат в группу после одобрения заявки: снимает признак выхода. */
    @Query("UPDATE groups SET isLeft = 0 WHERE id = :groupId")
    suspend fun clearLeft(groupId: String)

    @Query(
        "UPDATE groups SET lastMessagePreview = :preview, lastMessageAtMs = :atMs " +
            "WHERE id = :groupId"
    )
    suspend fun updateGroupLastMessage(groupId: String, preview: String, atMs: Long)

    @Query("UPDATE groups SET unreadCount = unreadCount + 1 WHERE id = :groupId")
    suspend fun incrementGroupUnread(groupId: String)

    @Query("UPDATE groups SET unreadCount = 0 WHERE id = :groupId")
    suspend fun markGroupRead(groupId: String)

    /**
     * Счётчик группы = сумма непрочитанных по её темам.
     *
     * Прочитали одну тему - обнулять всю группу нельзя: в остальных темах
     * сообщения могли остаться непрочитанными. Поэтому после сброса темы
     * счётчик группы просто пересчитывается.
     */
    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM group_topics WHERE groupId = :groupId")
    suspend fun sumTopicUnread(groupId: String): Int

    @Query("UPDATE groups SET unreadCount = :count WHERE id = :groupId")
    suspend fun setGroupUnread(groupId: String, count: Int)

    @Query("UPDATE groups SET memberCount = :count WHERE id = :groupId")
    suspend fun updateMemberCount(groupId: String, count: Int)

    // ── Участники ─────────────────────────────────────────────────────────────
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isBanned = 0 ORDER BY joinedAtMs ASC")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isBanned = 0 ORDER BY joinedAtMs ASC")
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>

    /**
     * Роли этого телефона во всех группах разом.
     *
     * Нужно главному экрану: раздел «Админ группы» показывается только тем,
     * кто группу создал или кого назначили администратором. Один запрос вместо
     * обхода всех групп.
     */
    @Query("SELECT * FROM group_members WHERE nodeId = :nodeId AND isBanned = 0")
    suspend fun getMyMemberships(nodeId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND nodeId = :nodeId")
    suspend fun getMember(groupId: String, nodeId: String): GroupMemberEntity?

    @Query(
        "SELECT * FROM group_members WHERE groupId = :groupId AND role IN ('OWNER','ADMIN') " +
            "AND isBanned = 0 ORDER BY joinedAtMs ASC"
    )
    suspend fun getAdmins(groupId: String): List<GroupMemberEntity>

    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId AND isBanned = 0")
    suspend fun countMembers(groupId: String): Int

    /** Поиск участников по имени или идентификатору узла. */
    @Query(
        "SELECT * FROM group_members WHERE groupId = :groupId AND isBanned = 0 AND " +
            "(displayName LIKE '%' || :query || '%' OR nodeId LIKE '%' || :query || '%') " +
            "ORDER BY displayName ASC"
    )
    suspend fun searchMembers(groupId: String, query: String): List<GroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND nodeId = :nodeId")
    suspend fun deleteMember(groupId: String, nodeId: String)

    @Query("UPDATE group_members SET role = :role, permissions = :permissions WHERE groupId = :groupId AND nodeId = :nodeId")
    suspend fun updateMemberRole(groupId: String, nodeId: String, role: String, permissions: Long)

    @Query("UPDATE group_members SET permissions = :permissions WHERE groupId = :groupId AND nodeId = :nodeId")
    suspend fun updateMemberPermissions(groupId: String, nodeId: String, permissions: Long)

    @Query("UPDATE group_members SET isBanned = :banned WHERE groupId = :groupId AND nodeId = :nodeId")
    suspend fun updateMemberBanned(groupId: String, nodeId: String, banned: Boolean)

    // ── Темы ──────────────────────────────────────────────────────────────────
    @Query("SELECT * FROM group_topics WHERE groupId = :groupId ORDER BY lastMessageAtMs DESC, createdAtMs ASC")
    fun observeTopics(groupId: String): Flow<List<GroupTopicEntity>>

    @Query("SELECT * FROM group_topics WHERE groupId = :groupId ORDER BY lastMessageAtMs DESC, createdAtMs ASC")
    suspend fun getTopics(groupId: String): List<GroupTopicEntity>

    @Query("SELECT * FROM group_topics WHERE id = :topicId")
    suspend fun getTopicById(topicId: String): GroupTopicEntity?

    @Query("SELECT * FROM group_topics WHERE groupId = :groupId AND isGeneral = 1 LIMIT 1")
    suspend fun getGeneralTopic(groupId: String): GroupTopicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopic(topic: GroupTopicEntity)

    @Query("UPDATE group_topics SET name = :name WHERE id = :topicId")
    suspend fun renameTopic(topicId: String, name: String)

    @Query("UPDATE group_topics SET isClosed = :closed WHERE id = :topicId")
    suspend fun updateTopicClosed(topicId: String, closed: Boolean)

    @Query(
        "UPDATE group_topics SET messageCount = messageCount + 1, " +
            "lastMessagePreview = :preview, lastMessageAtMs = :atMs WHERE id = :topicId"
    )
    suspend fun registerTopicMessage(topicId: String, preview: String, atMs: Long)

    @Query("UPDATE group_topics SET unreadCount = unreadCount + 1 WHERE id = :topicId")
    suspend fun incrementTopicUnread(topicId: String)

    @Query("UPDATE group_topics SET unreadCount = 0 WHERE id = :topicId")
    suspend fun markTopicRead(topicId: String)

    @Query("DELETE FROM group_topics WHERE id = :topicId")
    suspend fun deleteTopic(topicId: String)

    @Query("SELECT COALESCE(SUM(messageCount), 0) FROM group_topics WHERE groupId = :groupId")
    suspend fun totalTopicMessages(groupId: String): Int

    // ── Заявки на вступление ──────────────────────────────────────────────────
    @Query("SELECT * FROM group_join_requests WHERE groupId = :groupId AND status = 'PENDING' ORDER BY requestedAtMs DESC")
    fun observePendingRequests(groupId: String): Flow<List<GroupJoinRequestEntity>>

    @Query("SELECT * FROM group_join_requests WHERE groupId = :groupId AND status = 'PENDING' ORDER BY requestedAtMs DESC")
    suspend fun getPendingRequests(groupId: String): List<GroupJoinRequestEntity>

    @Query("SELECT COUNT(*) FROM group_join_requests WHERE groupId = :groupId AND status = 'PENDING'")
    suspend fun countPendingRequests(groupId: String): Int

    @Query("SELECT * FROM group_join_requests WHERE groupId = :groupId AND nodeId = :nodeId")
    suspend fun getJoinRequest(groupId: String, nodeId: String): GroupJoinRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJoinRequest(request: GroupJoinRequestEntity)

    @Query(
        "UPDATE group_join_requests SET status = :status, decidedAtMs = :decidedAtMs, " +
            "decidedBy = :decidedBy WHERE groupId = :groupId AND nodeId = :nodeId"
    )
    suspend fun updateJoinRequestStatus(
        groupId: String,
        nodeId: String,
        status: String,
        decidedAtMs: Long,
        decidedBy: String,
    )

    // ── Ссылки-приглашения ────────────────────────────────────────────────────
    @Query("SELECT * FROM group_invites WHERE groupId = :groupId ORDER BY createdAtMs DESC")
    fun observeInvites(groupId: String): Flow<List<GroupInviteEntity>>

    @Query("SELECT * FROM group_invites WHERE groupId = :groupId ORDER BY createdAtMs DESC")
    suspend fun getInvites(groupId: String): List<GroupInviteEntity>

    @Query("SELECT * FROM group_invites WHERE slug = :slug")
    suspend fun getInviteBySlug(slug: String): GroupInviteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvite(invite: GroupInviteEntity)

    @Query("UPDATE group_invites SET revoked = 1 WHERE slug = :slug")
    suspend fun revokeInvite(slug: String)

    /** Совсем убрать ссылку из списка: отозванная уже никого не пускает. */
    @Query("DELETE FROM group_invites WHERE slug = :slug")
    suspend fun deleteInvite(slug: String)

    @Query("UPDATE group_invites SET useCount = useCount + 1 WHERE slug = :slug")
    suspend fun registerInviteUse(slug: String)

    // ── Статистика для администраторов ────────────────────────────────────────
    @Query("SELECT * FROM group_message_stats WHERE groupId = :groupId AND topicId = :topicId AND dayKey = :dayKey")
    suspend fun getStat(groupId: String, topicId: String, dayKey: String): GroupMessageStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStat(stat: GroupMessageStatEntity)

    @Query("SELECT * FROM group_message_stats WHERE groupId = :groupId AND topicId = '' ORDER BY dayKey DESC LIMIT :limit")
    suspend fun getGroupStats(groupId: String, limit: Int): List<GroupMessageStatEntity>

    @Query("SELECT * FROM group_message_stats WHERE groupId = :groupId AND topicId != '' ORDER BY dayKey DESC LIMIT :limit")
    suspend fun getTopicStats(groupId: String, limit: Int): List<GroupMessageStatEntity>

    /** Счётчик сообщений и активных отправителей за период — для карточки статистики. */
    @Query(
        "SELECT COALESCE(SUM(messageCount), 0) FROM group_message_stats " +
            "WHERE groupId = :groupId AND topicId = '' AND dayKey >= :fromDayKey"
    )
    suspend fun sumMessagesSince(groupId: String, fromDayKey: String): Int

    // ── Удаление группы ───────────────────────────────────────────────────────
    /**
     * Удалить группу. Дочерние строки (участники, темы, заявки, ссылки,
     * статистика) убираются сами: у их таблиц внешний ключ на groups(id)
     * с ON DELETE CASCADE. Сообщения чистит MessageDao.deleteGroupMessages.
     */
    @Query("DELETE FROM groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    // ── Служебное ─────────────────────────────────────────────────────────────
    /** Пересчёт memberCount из таблицы участников; вызывать после добавления/удаления. */
    @Transaction
    suspend fun refreshMemberCount(groupId: String) {
        updateMemberCount(groupId, countMembers(groupId))
    }
}
