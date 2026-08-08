# -*- coding: utf-8 -*-
import os
import subprocess

BASE = r"C:\APUMIR\p2p-messenger"

print("=" * 70)
print("1. ПРОВЕРКА: добавлено ли логирование ROUTING DEBUG")
print("=" * 70)
CHAT_REPO = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                         "com", "vladimir", "messenger", "data", "repository", "ChatRepository.kt")
with open(CHAT_REPO, "r", encoding="utf-8") as f:
    content = f.read()
    
if "ROUTING DEBUG" in content:
    print("✓ Логирование ROUTING DEBUG присутствует в ChatRepository")
else:
    print("✗ Логирование ROUTING DEBUG ОТСУТСТВУЕТ")
    print("\nДобавляю логирование...")
    
    # Добавить логирование
    old_routing = '''            val chat = chatDao.getChatById(chatId)
            val rawId = if (recipientId.isBlank()) chat?.contactId ?: "" else recipientId
            val actualRecipientId = when {
                rawId.startsWith("pk_") -> rawId
                rawId.contains("node=pk_") -> "pk_" + rawId.substringAfter("node=pk_").substringBefore("&")
                else -> rawId
            }'''
    
    new_routing = '''            val chat = chatDao.getChatById(chatId)
            val rawId = if (recipientId.isBlank()) chat?.contactId ?: "" else recipientId
            
            Log.i(TAG, "📨 ROUTING DEBUG:")
            Log.i(TAG, "  chatId=$chatId")
            Log.i(TAG, "  input recipientId='$recipientId'")
            Log.i(TAG, "  chat.contactId='${chat?.contactId}'")
            Log.i(TAG, "  chat.contactName='${chat?.contactName}'")
            Log.i(TAG, "  rawId='$rawId'")
            
            val actualRecipientId = when {
                rawId.startsWith("pk_") -> rawId
                rawId.contains("node=pk_") -> "pk_" + rawId.substringAfter("node=pk_").substringBefore("&")
                else -> rawId
            }
            
            Log.i(TAG, "  actualRecipientId='$actualRecipientId'")'''
    
    if old_routing in content:
        content = content.replace(old_routing, new_routing)
        print("✓ Добавлено логирование ROUTING DEBUG")
    
    with open(CHAT_REPO, "w", encoding="utf-8") as f:
        f.write(content)

print("\n" + "=" * 70)
print("2. ПРОВЕРКА MessageBubble: клик по ссылке")
print("=" * 70)
BUBBLE_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                           "com", "vladimir", "messenger", "ui", "components", "MessageBubble.kt")
with open(BUBBLE_FILE, "r", encoding="utf-8") as f:
    bubble = f.read()

if "detectTapGestures" in bubble:
    print("✓ Используется detectTapGestures")
    if "onTap" in bubble and "onLongPress" in bubble:
        print("✓ Есть onTap и onLongPress")
    else:
        print("✗ Отсутствует onTap или onLongPress")
else:
    print("✗ НЕ используется detectTapGestures")

if "Intent.ACTION_VIEW" in bubble:
    print("✓ Есть Intent.ACTION_VIEW для открытия ссылок")
else:
    print("✗ НЕТ Intent.ACTION_VIEW")

print("\n" + "=" * 70)
print("3. ПРОВЕРКА RustBridge.sendMessageMqtt")
print("=" * 70)
BRIDGE_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                           "com", "vladimir", "messenger", "data", "RustBridge.kt")
with open(BRIDGE_FILE, "r", encoding="utf-8") as f:
    bridge = f.read()

# Найти функцию sendMessageMqtt
import re
match = re.search(r'fun sendMessageMqtt\([^)]+\)[\s\S]*?\n    \}', bridge)
if match:
    print("=== sendMessageMqtt ===")
    print(match.group(0))
else:
    print("Функция sendMessageMqtt не найдена")

print("\n" + "=" * 70)
print("4. ПОЛНЫЕ ЛОГИ (последние 100 строк)")
print("=" * 70)
result = subprocess.run(
    ["adb", "-s", "AUYF6R5923006121", "logcat", "-d", "-t", "100"],
    capture_output=True,
    text=True,
    shell=True
)
print(result.stdout[-3000:] if len(result.stdout) > 3000 else result.stdout)

print("\nDone.")