package com.vladimir.messenger.data.group

import android.util.Log
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.util.InlineImage

/**
 * Применение входящей правки сообщения группы/канала.
 *
 * Вынесено из GroupRepository (god-файл с потолком строк): здесь только
 * приём и проверки, всё остальное — у вызывающей стороны.
 *
 * Правит автор и владелец группы. Приходят только слова (и пометка о
 * продолжении длинного текста): служебные строки фото не трогаем.
 */
internal class GroupEditIncoming(
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    /** Правка заголовка темы канала и прочая общая работа — из репозитория. */
    private val applyEdit: suspend (message: MessageEntity, content: String) -> Unit,
    /** Запрос ленты: сообщение не доехало, а правка пришла. */
    private val requestHistory: suspend (groupId: String, topicId: String) -> Unit,
    private val maxMessageChars: Int,
) {

    /**
     * Вернуть true, если правка была применена (нужно вызывающей стороне для
     * журнала); false — пакет отклонён по правам, чату или пустоте текста.
     */
    suspend fun apply(
        senderId: String,
        me: String,
        groupId: String,
        topicId: String,
        messageId: String,
        text: String,
    ): Boolean {
        val group = groupDao.getGroupById(groupId) ?: return false
        if (groupDao.getMember(groupId, me) == null) return false
        val message = messageDao.getMessageById(messageId)
        if (message == null) {
            // Сообщение не доехало, а правка пришла: иначе участник остался
            // бы без текста. Просим ленту — пришедшая версия уже исправлена.
            Log.i(TAG, "edit for unknown message $messageId - requesting history")
            requestHistory(groupId, topicId)
            return false
        }
        if (message.chatId != groupId) return false
        // Править вправе автор и владелец группы — остальное отбрасываем.
        if (senderId != message.senderId && senderId != group.ownerId) return false
        val content = InlineImage.replaceText(message.content, text)
        if (content.isBlank() || content.length > maxMessageChars) return false
        applyEdit(message, content)
        // Куски текста прежних редакций больше не подклеиваются — убираем.
        val keepRev = InlineImage.textTail(content)?.rev
        for (row in messageDao.getByContentPattern(group.id, InlineImage.textPartPattern(messageId))) {
            val part = InlineImage.parseTextPart(row.content) ?: continue
            if (part.headId == messageId && part.rev != keepRev) messageDao.deleteById(row.id)
        }
        return true
    }

    private companion object {
        const val TAG = "GroupEditIncoming"
    }
}
