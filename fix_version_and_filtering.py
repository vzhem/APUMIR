# -*- coding: utf-8 -*-
import os

BASE = r"C:\APUMIR\p2p-messenger"

# ========== 1. Синхронизировать versionName с тегом v8.6 ==========
print("=" * 70)
print("1. Синхронизация versionName → v8.6")
print("=" * 70)
GRADLE_FILE = os.path.join(BASE, "android-app", "app", "build.gradle.kts")
with open(GRADLE_FILE, "r", encoding="utf-8") as f:
    g = f.read()

# Найти текущую версию
import re
match = re.search(r'versionName = "([^"]+)"', g)
if match:
    current = match.group(1)
    print(f"Текущий versionName: {current}")
    if current != "v8.6":
        g = g.replace(f'versionName = "{current}"', 'versionName = "v8.6"')
        print(f"✓ Обновлён: {current} → v8.6")
    else:
        print("✓ Уже v8.6")
else:
    print("✗ versionName не найден")

with open(GRADLE_FILE, "w", encoding="utf-8") as f:
    f.write(g)

# ========== 2. Найти где сохраняются ВХОДЯЩИЕ сообщения ==========
print("\n" + "=" * 70)
print("2. Поиск функции сохранения входящих сообщений")
print("=" * 70)
CSS_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                        "com", "vladimir", "messenger", "service", "CoreServerService.kt")

if os.path.exists(CSS_FILE):
    with open(CSS_FILE, "r", encoding="utf-8") as f:
        css_content = f.read()
    
    # Ищем message_received или insertMessage
    import re
    
    # Найти обработчик message_received
    match = re.search(r'["\']?message_received["\']?[\s\S]{0,500}?insertMessage[\s\S]{0,300}', css_content)
    if match:
        print("=== Обработчик message_received ===")
        print(match.group(0))
    
    # Найти все места где вставляется сообщение
    for match in re.finditer(r'messageDao\.insertMessage\([^)]+\)', css_content):
        print(f"\n=== insertMessage в CoreServerService ===")
        start = max(0, match.start() - 300)
        end = min(len(css_content), match.end() + 100)
        print(css_content[start:end])
        print("---")
else:
    print("CoreServerService.kt не найден")

# ========== 3. Показать ChatRepository.receiveMessage если есть ==========
print("\n" + "=" * 70)
print("3. ChatRepository: обработка входящих сообщений")
print("=" * 70)
CHAT_REPO = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                         "com", "vladimir", "messenger", "data", "repository", "ChatRepository.kt")
with open(CHAT_REPO, "r", encoding="utf-8") as f:
    repo_content = f.read()

# Ищем функции связанные с получением
for func_name in ["receiveMessage", "handleIncoming", "onMessageReceived", "processIncoming"]:
    if func_name in repo_content:
        print(f"\n=== Функция {func_name} ===")
        match = re.search(rf'fun {func_name}\([\s\S]*?\n    \}}', repo_content)
        if match:
            print(match.group(0)[:500])

# ========== 4. Удалить старые debug файлы ==========
print("\n" + "=" * 70)
print("4. Удаление старых debug файлов")
print("=" * 70)
old_files = [
    "diagnose_all_issues.py",
    "diagnose_links_and_routing.py",
    "full_diagnosis.py",
]
for fname in old_files:
    fpath = os.path.join(BASE, fname)
    if os.path.exists(fpath):
        os.remove(fpath)
        print(f"🗑 Удалён: {fname}")

print("\nDone.")