# -*- coding: utf-8 -*-
import os
import re

BASE = r"C:\APUMIR\p2p-messenger"

# ========== 1. Показать как CoreServerService обрабатывает message_received ==========
print("=" * 70)
print("1. CoreServerService: обработка message_received")
print("=" * 70)
CSS_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                        "com", "vladimir", "messenger", "service", "CoreServerService.kt")
with open(CSS_FILE, "r", encoding="utf-8") as f:
    css = f.read()

# Найти обработчик message_received
match = re.search(r'"message_received"[\s\S]{0,600}?saveIncomingMessage[\s\S]{0,200}', css)
if match:
    print(match.group(0))

# ========== 2. Показать RustBridge — где читается event ==========
print("\n" + "=" * 70)
print("2. RustBridge: чтение event (messageId, senderId и т.д.)")
print("=" * 70)
BRIDGE_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                           "com", "vladimir", "messenger", "data", "RustBridge.kt")
with open(BRIDGE_FILE, "r", encoding="utf-8") as f:
    bridge = f.read()

# Показать CoreEventFfi класс (сгенерированный)
KOTLIN_OUT = os.path.join(BASE, "android-app", "app", "src", "main", "java", "uniffi", "p2p_core")
if os.path.exists(KOTLIN_OUT):
    for file in os.listdir(KOTLIN_OUT):
        if file == "p2p_core.kt":
            with open(os.path.join(KOTLIN_OUT, file), "r", encoding="utf-8") as f:
                kotlin = f.read()
            match = re.search(r'data class CoreEventFfi[\s\S]{0,500}', kotlin)
            if match:
                print("=== CoreEventFfi в Kotlin ===")
                print(match.group(0)[:400])

# ========== 3. Показать event в CoreServerService ==========
print("\n" + "=" * 70)
print("3. Показать как event парсится")
print("=" * 70)
# Найти все упоминания event.senderId или event.messageId
for match in re.finditer(r'event\.(senderId|messageId|chatId|text|timestamp|recipientId)', css):
    i = css.find(match.group(0))
    start = max(0, i - 100)
    end = min(len(css), i + 100)
    print(f"\n--- {match.group(0)} ---")
    print(css[start:end])

# ========== 4. Проверить saveIncomingMessage ==========
print("\n" + "=" * 70)
print("4. ChatRepository.saveIncomingMessage")
print("=" * 70)
CHAT_REPO = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                         "com", "vladimir", "messenger", "data", "repository", "ChatRepository.kt")
with open(CHAT_REPO, "r", encoding="utf-8") as f:
    repo = f.read()

match = re.search(r'suspend fun saveIncomingMessage[\s\S]{0,400}', repo)
if match:
    print(match.group(0))

print("\nDone.")