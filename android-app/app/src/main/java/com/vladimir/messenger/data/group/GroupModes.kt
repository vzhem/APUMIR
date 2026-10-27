package com.vladimir.messenger.data.group

import android.util.Log
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.util.SosMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Медленный режим и красная кнопка SOS — вынесены из [GroupRepository]
 * (аудит 2026-10-26, п. 4.1).
 *
 * Обе настройки — «одна на всех»: включаются администратором, уходят конвертом
 * всей группе и действуют на каждого участника одинаково, поэтому живут
 * вместе. Правила счёта (когда можно писать, когда можно нажимать) остаются
 * чистыми функциями в [GroupSlowMode] и [GroupSos] — здесь только хранение,
 * проверка прав и рассылка.
 *
 * Поведение перенесено один в один, включая тексты ошибок: их видят
 * пользователи, а тесты сверяют сообщения. Права те же: владелец и
 * администраторы с правом менять информацию о группе.
 *
 * [sendMessage] приходит функцией, потому что тревога отправляется обычными
 * сообщениями с пометкой `sos` — вызов большого `sendMessage` остался в
 * репозитории, а не переехал сюда. Возвращает он id сообщения (`Result<String>`),
 * а не Unit: для счёта отправленных нужен только успех, но тип не меняем —
 * иначе обёртка в репозитории перестанет совпадать.
 */
internal class GroupModes(
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val clock: () -> Long,
    private val nodeId: () -> String?,
    private val backgroundScope: CoroutineScope,
    private val deliver: suspend (groupId: String, envelope: String, recipients: List<String>) -> Unit,
    private val recipients: suspend (groupId: String, me: String) -> List<String>,
    private val memberMask: (GroupEntity) -> Long,
    private val sendMessage: suspend (groupId: String, topicId: String, text: String) -> Result<String>,
) {

    // ── медленный режим ─────────────────────────────────────────────────────

    /**
     * Сколько миллисекунд ЭТОМУ участнику осталось ждать перед следующим
     * сообщением (0 - можно писать). Отсчёт от предыдущего сообщения в
     * группе: тема не важна, иначе режим обходили переходом в другую тему.
     */
    suspend fun remainingMs(groupId: String, me: String): Long {
        val group = groupDao.getGroupById(groupId) ?: return 0L
        val member = groupDao.getMember(groupId, me) ?: return 0L
        if (GroupRole.isAdminOrOwner(member.role)) return 0L
        return remainingMs(group, me, clock())
    }

    /** То же по уже прочитанной группе: `sendMessage` экономит запрос к базе. */
    suspend fun remainingMs(group: GroupEntity, me: String, now: Long): Long {
        if (group.slowModeSeconds <= 0) return 0L
        val last = messageDao.lastOutgoingAt(group.id, me, now)
        return GroupSlowMode.remainingMs(group.slowModeSeconds, last, now)
    }

    suspend fun setSlowMode(groupId: String, seconds: Int): Result<Unit> {
        val me = nodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canChangeInfo(member.role, member.permissions, memberMask(group))) {
            return Result.failure(SecurityException("Менять настройки группы вправе администратор"))
        }
        val safe = seconds.coerceIn(0, GroupWire.SLOW_MODE_MAX_SECONDS)
        if (safe == group.slowModeSeconds) return Result.success(Unit)
        groupDao.setSlowMode(groupId, safe)
        // Режим должен действовать на всех, поэтому уходит конвертом: у
        // вступившего позже он появится с первым же изменением (или при
        // одобрении заявки - см. decideJoinRequest).
        backgroundScope.launch {
            runCatching {
                deliver(groupId, GroupWire.buildSlowMode(groupId, safe), recipients(groupId, me))
            }.onFailure { Log.w(TAG, "slow mode fanout failed: ${it.message}") }
        }
        Log.i(TAG, "slow mode group=$groupId seconds=$safe by=$me")
        return Result.success(Unit)
    }

    // ── красная кнопка SOS ──────────────────────────────────────────────────

    /**
     * Настройки красной кнопки SOS: показывать ли кнопку, сколько тревожных
     * сообщений уходит за одно нажатие (3..5) и кто вправе нажимать.
     * Менять вправе те же, кто меняет информацию о группе (как медленный
     * режим): настройка одна на всех, поэтому уходит конвертом всей группе.
     */
    suspend fun setSosSettings(
        groupId: String,
        enabled: Boolean,
        count: Int,
        pressRole: String,
    ): Result<Unit> {
        val me = nodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canChangeInfo(member.role, member.permissions, memberMask(group))) {
            return Result.failure(SecurityException("Менять настройки группы вправе администратор"))
        }
        val safeCount = GroupSos.clampCount(count)
        val safeRole = GroupSos.normalizePressRole(pressRole)
        if (enabled == group.sosEnabled && safeCount == group.sosMessageCount &&
            safeRole == GroupSos.normalizePressRole(group.sosPressRole)
        ) {
            return Result.success(Unit)
        }
        groupDao.setSosSettings(groupId, enabled, safeCount, safeRole)
        backgroundScope.launch {
            runCatching {
                deliver(
                    groupId,
                    GroupWire.buildSosSettings(groupId, enabled, safeCount, safeRole),
                    recipients(groupId, me),
                )
            }.onFailure { Log.w(TAG, "sos settings fanout failed: ${it.message}") }
        }
        Log.i(TAG, "sos settings group=$groupId enabled=$enabled count=$safeCount role=$safeRole by=$me")
        return Result.success(Unit)
    }

    /**
     * Нажатие красной кнопки SOS: за один раз уходит [GroupEntity.sosMessageCount]
     * тревожных сообщений подряд - их видят и слышат все участники, даже если
     * телефон спит или группа у них без звука (см. показ уведомления).
     *
     * Защита от повторного нажатия - не меньше [GroupSos.COOLDOWN_MS]:
     * отметка ставится ДО отправки, чтобы и неудавшаяся попытка, и закрытие
     * приложения на середине не дали нажать снова сразу.
     *
     * Тревога не подчиняется тихому времени, медленному режиму, закрытой
     * теме и запрету обычных сообщений: ситуация чрезвычайная, а кто вправе
     * нажимать, решает отдельная настройка [GroupEntity.sosPressRole].
     */
    suspend fun sendSos(groupId: String, topicId: String): Result<Int> {
        val me = nodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        if (group.isChannel) {
            return Result.failure(IllegalStateException("В канале кнопки SOS нет"))
        }
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (member.isBanned) return Result.failure(SecurityException("Вы ограничены в этой группе"))
        if (!group.sosEnabled) {
            return Result.failure(IllegalStateException("Кнопка SOS скрыта настройками группы"))
        }
        if (!GroupSos.canPress(member.role, group.sosPressRole, member.isBanned)) {
            return Result.failure(SecurityException("Нажимать SOS в этой группе вам не разрешено"))
        }
        val now = clock()
        val waitMs = GroupSos.cooldownRemainingMs(group.sosLastSentAtMs, now)
        if (waitMs > 0L) {
            val seconds = (waitMs + 999L) / 1000L
            return Result.failure(SecurityException("SOS уже отправлен: подождите $seconds с"))
        }
        groupDao.setSosLastSentAt(groupId, now)
        val count = GroupSos.clampCount(group.sosMessageCount)
        val text = SosMessage.wrap(GroupSos.effectiveText(group.sosText))
        var sent = 0
        repeat(count) {
            sendMessage(groupId, topicId, text)
                .onSuccess { sent++ }
                .onFailure { e ->
                    return if (sent > 0) Result.success(sent) else Result.failure(e)
                }
        }
        Log.i(TAG, "sos sent group=$groupId topic=$topicId count=$sent by=$me")
        return Result.success(sent)
    }

    /**
     * Свой текст тревожного сообщения: [text] из поля в настройках группы
     * (пусто - вернуть текст по умолчанию). Менять вправе те же, кто меняет
     * информацию о группе; текст общий на всех, поэтому уходит конвертом.
     */
    suspend fun setSosText(groupId: String, text: String): Result<Unit> {
        val me = nodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canChangeInfo(member.role, member.permissions, memberMask(group))) {
            return Result.failure(SecurityException("Менять настройки группы вправе администратор"))
        }
        val safe = text.trim().take(GroupSos.MAX_TEXT_CHARS)
        if (safe == group.sosText) return Result.success(Unit)
        groupDao.setSosText(groupId, safe)
        backgroundScope.launch {
            runCatching {
                deliver(groupId, GroupWire.buildSosText(groupId, safe), recipients(groupId, me))
            }.onFailure { Log.w(TAG, "sos text fanout failed: ${it.message}") }
        }
        Log.i(TAG, "sos text group=$groupId chars=${safe.length} by=$me")
        return Result.success(Unit)
    }

    /** Сколько миллисекунд этому телефону ещё нельзя нажимать SOS в группе. */
    suspend fun sosCooldownRemainingMs(groupId: String): Long {
        val group = groupDao.getGroupById(groupId) ?: return 0L
        return GroupSos.cooldownRemainingMs(group.sosLastSentAtMs, clock())
    }

    // ── приём пакетов ───────────────────────────────────────────────────────

    suspend fun handleSlowMode(senderId: String, packet: GroupWire.Packet.SlowMode) {
        val me = nodeId().orEmpty()
        if (me.isBlank()) return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (groupDao.getMember(packet.groupId, me) == null) return
        // Верим владельцу и администраторам с правом менять информацию -
        // ровно тем, кто вправе режим включать.
        if (senderId != group.ownerId) {
            val sender = groupDao.getMember(packet.groupId, senderId) ?: return
            if (!GroupRole.isAdminOrOwner(sender.role)) return
            if (!GroupPermissions.canChangeInfo(sender.role, sender.permissions, memberMask(group))) return
        }
        if (packet.seconds == group.slowModeSeconds) return
        groupDao.setSlowMode(packet.groupId, packet.seconds)
        Log.i(TAG, "slow mode applied group=${packet.groupId} seconds=${packet.seconds} from=$senderId")
    }

    suspend fun handleSosSettings(senderId: String, packet: GroupWire.Packet.SosSettings) {
        val me = nodeId().orEmpty()
        if (me.isBlank()) return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (groupDao.getMember(packet.groupId, me) == null) return
        // Верим владельцу и администраторам с правом менять информацию -
        // ровно тем, кто вправе кнопку настраивать.
        if (senderId != group.ownerId) {
            val sender = groupDao.getMember(packet.groupId, senderId) ?: return
            if (!GroupRole.isAdminOrOwner(sender.role)) return
            if (!GroupPermissions.canChangeInfo(sender.role, sender.permissions, memberMask(group))) return
        }
        if (packet.enabled == group.sosEnabled &&
            packet.count == GroupSos.clampCount(group.sosMessageCount) &&
            packet.pressRole == GroupSos.normalizePressRole(group.sosPressRole)
        ) {
            return
        }
        groupDao.setSosSettings(packet.groupId, packet.enabled, packet.count, packet.pressRole)
        Log.i(
            TAG,
            "sos settings applied group=${packet.groupId} enabled=${packet.enabled} " +
                "count=${packet.count} role=${packet.pressRole} from=$senderId",
        )
    }

    suspend fun handleSosText(senderId: String, packet: GroupWire.Packet.SosText) {
        val me = nodeId().orEmpty()
        if (me.isBlank()) return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (groupDao.getMember(packet.groupId, me) == null) return
        // Верим владельцу и администраторам с правом менять информацию -
        // ровно тем, кто вправе текст тревоги менять.
        if (senderId != group.ownerId) {
            val sender = groupDao.getMember(packet.groupId, senderId) ?: return
            if (!GroupRole.isAdminOrOwner(sender.role)) return
            if (!GroupPermissions.canChangeInfo(sender.role, sender.permissions, memberMask(group))) return
        }
        if (packet.text == group.sosText) return
        groupDao.setSosText(packet.groupId, packet.text)
        Log.i(TAG, "sos text applied group=${packet.groupId} chars=${packet.text.length} from=$senderId")
    }

    private companion object {
        private const val TAG = "GroupModes"
    }
}
