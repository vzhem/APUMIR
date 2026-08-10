# -*- coding: utf-8 -*-
import os
import re

BASE = r"C:\APUMIR\p2p-messenger"

# ========== 1. MessageDao: изменить стратегию для входящих ==========
print("=" * 70)
print("1. Добавить insertMessageIfNotExists в MessageDao")
print("=" * 70)

dao_file = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                        "com", "vladimir", "messenger", "data", "local", "dao", "MessageDao.kt")
with open(dao_file, "r", encoding="utf-8") as f:
    dao = f.read()

# Добавить новый метод вставки с IGNORE (не перезаписывать существующие)
new_method = '''
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIgnore(message: MessageEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId)")
    suspend fun messageExists(messageId: String): Boolean
'''

if "insertMessageIgnore" not in dao:
    # Вставить после insertMessage
    old = '''    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)'''
    
    if old in dao:
        dao = dao.replace(old, old + new_method)
        print("✓ Добавлен: insertMessageIgnore (IGNORE — не перезаписывает)")
        print("✓ Добавлен: messageExists (проверка наличия)")
    else:
        print("⚠ Не удалось найти место для вставки")
else:
    print("✓ Методы уже существуют")

with open(dao_file, "w", encoding="utf-8") as f:
    f.write(dao)

# ========== 2. Изменить saveIncomingMessage — не перезаписывать существующие ==========
print("\n" + "=" * 70)
print("2. Изменить saveIncomingMessage — защита от перезаписи")
print("=" * 70)

repo_file = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                         "com", "vladimir", "messenger", "data", "repository", "ChatRepository.kt")
with open(repo_file, "r", encoding="utf-8") as f:
    repo = f.read()

old_save = '''    suspend fun saveIncomingMessage(
        chatId: String,
        senderId: String,
        messageId: String,
        content: String,
        timestamp: Long,
        channel: MessageChannel = MessageChannel.UNKNOWN,
        recipientId: String = "",
    ) {
        android.util.Log.i(TAG, "💾 saveIncomingMessage: chatId=$chatId sender=$senderId msgId=$messageId")
        val entity = MessageEntity(
            id = messageId,
            chatId = chatId,
            senderId = senderId,
            content = content,
            timestamp = timestamp,
            isFromMe = false,
            status = MessageStatus.DELIVERED.name,
            recipientId = recipientId,
        )
        messageDao.insertMessage(entity)
        android.util.Log.i(TAG, "💾 insertMessage OK for chatId=$chatId msgId=$messageId")
        chatDao.updateLastMessage(chatId, content, timestamp)
        android.util.Log.i(TAG, "💾 updateLastMessage OK for chatId=$chatId — Room должен триггерить Flow")'''

new_save = '''    suspend fun saveIncomingMessage(
        chatId: String,
        senderId: String,
        messageId: String,
        content: String,
        timestamp: Long,
        channel: MessageChannel = MessageChannel.UNKNOWN,
        recipientId: String = "",
    ) {
        // Проверяем — если сообщение уже существует, не перезаписываем
        // (защита от перезаписи timestamp при FULL SYNC дубликатах)
        val exists = messageDao.messageExists(messageId)
        if (exists) {
            android.util.Log.i(TAG, "⏩ saveIncomingMessage: SKIP — msg $messageId уже существует (дубликат)")
            return
        }
        
        android.util.Log.i(TAG, "💾 saveIncomingMessage: chatId=$chatId sender=$senderId msgId=$messageId ts=$timestamp")
        val entity = MessageEntity(
            id = messageId,
            chatId = chatId,
            senderId = senderId,
            content = content,
            timestamp = timestamp,
            isFromMe = false,
            status = MessageStatus.DELIVERED.name,
            recipientId = recipientId,
        )
        // Используем IGNORE — если вдруг дубликат пришёл одновременно, не перезаписываем
        messageDao.insertMessageIgnore(entity)
        android.util.Log.i(TAG, "💾 insertMessageIgnore OK for chatId=$chatId msgId=$messageId")
        chatDao.updateLastMessage(chatId, content, timestamp)
        android.util.Log.i(TAG, "💾 updateLastMessage OK for chatId=$chatId")'''

if old_save in repo:
    repo = repo.replace(old_save, new_save)
    print("✓ saveIncomingMessage: защита от перезаписи + пропуск дубликатов")
else:
    print("⚠ saveIncomingMessage паттерн не найден")

# ========== 3. Передавать timestamp в payload + сохранять оригинальный ==========
print("\n" + "=" * 70)
print("3. Сохранять ОРИГИНАЛЬНЫЙ timestamp из payload (не время получения)")
print("=" * 70)

css_file = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                        "com", "vladimir", "messenger", "service", "CoreServerService.kt")
with open(css_file, "r", encoding="utf-8") as f:
    css = f.read()

# В обработчике message_received — timestamp приходит от Rust (оригинальное время отправки)
# Он уже передаётся через event.timestamp, просто проверяем что используется
old_receive = '''            "message_received" -> {
                Log.i(TAG, "📨 MESSAGE_RECEIVED: sender=${event.senderId} chat=${event.chatId} msgId=${event.messageId} text=${event.text?.take(30)}")
                val senderId = event.senderId ?: return
                val chatId = event.chatId ?: return
                val messageId = event.messageId ?: return
                val text = event.text ?: return
                val timestamp = event.timestamp ?: System.currentTimeMillis()'''

# Улучшим логирование — показать timestamp
new_receive = '''            "message_received" -> {
                val originalTs = event.timestamp
                val ts = originalTs ?: System.currentTimeMillis()
                Log.i(TAG, "📨 MESSAGE_RECEIVED: sender=${event.senderId} msgId=${event.messageId} originalTs=$originalTs ts=$ts text=${event.text?.take(30)}")
                val senderId = event.senderId ?: return
                val chatId = event.chatId ?: return
                val messageId = event.messageId ?: return
                val text = event.text ?: return
                val timestamp = ts'''

if old_receive in css:
    css = css.replace(old_receive, new_receive)
    print("✓ Улучшено логирование timestamp в message_received")
else:
    print("⚠ message_received паттерн не найден")

with open(css_file, "w", encoding="utf-8") as f:
    f.write(css)

with open(repo_file, "w", encoding="utf-8") as f:
    f.write(repo)

# ========== 4. versionName → v11.5 ==========
print("\n" + "=" * 70)
print("4. versionName → v11.5")
print("=" * 70)
GRADLE_FILE = os.path.join(BASE, "android-app", "app", "build.gradle.kts")
with open(GRADLE_FILE, "r", encoding="utf-8") as f:
    g = f.read()

match = re.search(r'versionName = "([^"]+)"', g)
if match:
    current = match.group(1)
    g = g.replace(f'versionName = "{current}"', 'versionName = "v11.5"')
    print(f"✓ Обновлён: {current} → v11.5")

with open(GRADLE_FILE, "w", encoding="utf-8") as f:
    f.write(g)

print("\nDone.")