# -*- coding: utf-8 -*-
import os
import re

BASE = r"C:\APUMIR\p2p-messenger"

# ========== 1. Найти где Rust генерирует message_received ==========
print("=" * 70)
print("1. Поиск 'message_received' в Rust core")
print("=" * 70)
P2P_SRC = os.path.join(BASE, "p2p-core", "src")
if not os.path.exists(P2P_SRC):
    P2P_SRC = BASE  # может быть в корне

for root, dirs, files in os.walk(P2P_SRC):
    # Пропускаем target и build директории
    if "target" in root or "build" in root or ".git" in root or "android-app" in root:
        continue
    for file in files:
        if file.endswith(".rs"):
            fpath = os.path.join(root, file)
            try:
                with open(fpath, "r", encoding="utf-8") as f:
                    content = f.read()
                if "MessageReceived" in content or "message_received" in content:
                    print(f"\n📄 {fpath}")
                    lines = content.split("\n")
                    for i, line in enumerate(lines):
                        if "MessageReceived" in line or "message_received" in line:
                            start = max(0, i - 3)
                            end = min(len(lines), i + 8)
                            print(f"--- строки {start+1}-{end+1} ---")
                            for j in range(start, end):
                                marker = ">>>" if j == i else "   "
                                print(f"{marker} {j+1}: {lines[j]}")
            except Exception as e:
                pass

# ========== 2. Найти event struct ==========
print("\n" + "=" * 70)
print("2. Поиск Event enum / struct в Rust")
print("=" * 70)

for root, dirs, files in os.walk(P2P_SRC):
    if "target" in root or "build" in root or ".git" in root or "android-app" in root:
        continue
    for file in files:
        if file.endswith(".rs"):
            fpath = os.path.join(root, file)
            try:
                with open(fpath, "r", encoding="utf-8") as f:
                    content = f.read()
                
                match = re.search(r'(pub\s+)?enum\s+Event[\s\S]{0,800}?\n\s*\}', content)
                if match:
                    print(f"\n📄 {fpath}")
                    print(match.group(0))
            except:
                pass

# ========== 3. Найти UDL (uniffi) описание ==========
print("\n" + "=" * 70)
print("3. Поиск UDL (uniffi binding)")
print("=" * 70)

for root, dirs, files in os.walk(P2P_SRC):
    if "target" in root or "build" in root or ".git" in root or "android-app" in root:
        continue
    for file in files:
        if file.endswith(".udl"):
            fpath = os.path.join(root, file)
            print(f"\n📄 {fpath}")
            with open(fpath, "r", encoding="utf-8") as f:
                print(f.read())

# ========== 4. Найти где Rust отправляет сообщения ==========
print("\n" + "=" * 70)
print("4. Rust: отправка сообщений (MQTT topic)")
print("=" * 70)

for root, dirs, files in os.walk(P2P_SRC):
    if "target" in root or "build" in root or ".git" in root or "android-app" in root:
        continue
    for file in files:
        if file.endswith(".rs") and "mqtt" in file.lower():
            fpath = os.path.join(root, file)
            print(f"\n📄 {fpath}")
            with open(fpath, "r", encoding="utf-8") as f:
                content = f.read()
            # Показать publish и subscribe
            for match in re.finditer(r'(pub\s+fn\s+\w*send\w*|pub\s+fn\s+\w*publish\w*)[\s\S]{0,400}', content):
                print(match.group(0)[:300])
                print("---")
            
            # Показать topic
            for match in re.finditer(r'p2pm2/[^\s"\']+|topic[\s\S]{0,50}', content):
                if "p2pm2" in match.group(0) or "topic" in match.group(0).lower():
                    print(f"Topic: {match.group(0)[:100]}")

print("\nDone.")