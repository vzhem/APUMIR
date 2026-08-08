# -*- coding: utf-8 -*-
import os

BASE = r"C:\APUMIR\p2p-messenger"

print("=" * 70)
print("1. MessageBubble.kt — ПОЛНЫЙ КОД")
print("=" * 70)
BUBBLE_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                           "com", "vladimir", "messenger", "ui", "components", "MessageBubble.kt")
with open(BUBBLE_FILE, "r", encoding="utf-8") as f:
    print(f.read())

print("\n" + "=" * 70)
print("2. Rust: MQTT подписка (topic структура)")
print("=" * 70)
RUST_DIR = os.path.join(BASE, "p2p-core", "src")
if os.path.exists(RUST_DIR):
    for root, dirs, files in os.walk(RUST_DIR):
        for file in files:
            if file.endswith(".rs") and ("mqtt" in file.lower() or "transport" in file.lower()):
                fpath = os.path.join(root, file)
                print(f"\n=== {fpath} ===")
                with open(fpath, "r", encoding="utf-8") as f:
                    content = f.read()
                # Показать только subscribe/publish функции
                lines = content.split("\n")
                for i, line in enumerate(lines):
                    if "subscribe" in line.lower() or "publish" in line.lower() or "topic" in line.lower():
                        # Показать контекст
                        start = max(0, i-2)
                        end = min(len(lines), i+5)
                        for j in range(start, end):
                            print(f"{j}: {lines[j]}")
                        print()

print("\n" + "=" * 70)
print("3. ChatRepository: полная функция sendMessage")
print("=" * 70)
CHAT_REPO = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                         "com", "vladimir", "messenger", "data", "repository", "ChatRepository.kt")
with open(CHAT_REPO, "r", encoding="utf-8") as f:
    content = f.read()
# Показать функцию sendMessage
import re
match = re.search(r'suspend fun sendMessage\([\s\S]*?\n    \}', content)
if match:
    print(match.group(0))

print("\nDone.")